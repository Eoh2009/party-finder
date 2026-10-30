package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Looks up a player: name -> UUID (Mojang), then UUID -> SkyBlock profiles (Hypixel).
 * Everything is async: methods return a CompletableFuture (a "result that arrives later")
 * instead of blocking the game while waiting for the internet.
 */
public final class ProfileLookup {
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** What a successful lookup gives back. rawJson is null unless keepRaw was asked for. */
	public record Result(String name, String uuid, DungeonStats stats, Gear gear, Accessories accessories,
			Timings timings, String rawJson) {}

	/** What gets cached per player: the parsed stats, gear and accessories (not the big raw response). */
	private record Parsed(DungeonStats stats, Gear gear, Accessories accessories) {}

	/**
	 * How long each step took, in ms. -1 means the step didn't run (answer came from the cache).
	 * "volatile" because background threads write these and another thread reads them.
	 */
	public static final class Timings {
		public volatile long mojangMs = -1;
		public volatile long hypixelMs = -1;
		public volatile long parseMs = -1;
		public volatile long gearMs = -1; // decoding the inventory/backpack/accessory item blobs
		public volatile int retries = 0;
	}

	// A cache entry stores the pending/finished request plus when it expires.
	// Storing the future (not just the answer) means two lookups of the same player
	// at the same moment share one request instead of sending two.
	private record Cached<T>(CompletableFuture<T> future, long expiresAt) {}

	private static final Map<String, Cached<String>> UUID_CACHE = new ConcurrentHashMap<>();        // key: lowercase name
	private static final Map<String, Cached<Parsed>> STATS_CACHE = new ConcurrentHashMap<>();       // key: uuid

	/**
	 * Looks up a player's stats. With keepRaw = true (for /pftest <name> save) the stats cache
	 * is skipped so Hypixel is really asked, and its raw response text is put in Result.rawJson.
	 */
	public static CompletableFuture<Result> lookup(String name, boolean keepRaw) {
		// Same rule as Minecraft names. Also stops odd characters from breaking the URL.
		if (!name.matches("\\w{1,16}")) {
			return CompletableFuture.failedFuture(new LookupException("invalid name"));
		}
		Timings timings = new Timings();
		// A small "box" the Hypixel step puts the raw response into. null = not wanted.
		AtomicReference<String> raw = keepRaw ? new AtomicReference<>() : null;
		// "Async" = continue on a background thread, never the game thread
		// (fetchProfiles reads the config file).
		return getUuid(name, timings).thenComposeAsync(uuid ->
			getStats(uuid, raw, timings).thenApply(parsed ->
				new Result(name, uuid, parsed.stats(), parsed.gear(), parsed.accessories(), timings,
					raw == null ? null : raw.get())));
	}

	private static CompletableFuture<String> getUuid(String name, Timings timings) {
		return cached(UUID_CACHE, name.toLowerCase(Locale.ROOT), () -> {
			long start = System.currentTimeMillis();
			return fetchUuid(name, timings).whenComplete((uuid, error) ->
				timings.mojangMs = System.currentTimeMillis() - start);
		});
	}

	/** Only the small parsed stats are cached, not the large raw response. */
	private static CompletableFuture<Parsed> getStats(String uuid, AtomicReference<String> raw, Timings timings) {
		if (raw != null) {
			// Forget the cached stats, so the request below really goes to Hypixel.
			STATS_CACHE.remove(uuid);
		}
		return cached(STATS_CACHE, uuid, () -> {
			long hypixelStart = System.currentTimeMillis();
			return fetchProfiles(uuid, timings).thenApply(json -> {
				timings.hypixelMs = System.currentTimeMillis() - hypixelStart;
				if (raw != null) {
					raw.set(json);
				}
				return parse(json, uuid, timings);
			});
		});
	}

	private static Parsed parse(String json, String uuid, Timings timings) {
		try {
			long parseStart = System.currentTimeMillis();
			JsonObject member = DungeonStats.selectedMember(json, uuid);
			DungeonStats stats = DungeonStats.parse(member);
			timings.parseMs = System.currentTimeMillis() - parseStart;

			// Timed separately: there can be many backpacks to decode.
			long gearStart = System.currentTimeMillis();
			Gear gear = Gear.parse(member);
			Accessories accessories = Accessories.parse(member);
			timings.gearMs = System.currentTimeMillis() - gearStart;
			return new Parsed(stats, gear, accessories);
		} catch (LookupException e) {
			throw e;
		} catch (RuntimeException e) {
			// The JSON wasn't shaped like we expected.
			PartyFinder.LOGGER.error("Could not parse profile for {}", uuid, e);
			throw new LookupException("couldn't read profile data");
		}
	}

	/** Returns the cached request if it's still fresh, otherwise starts a new one with fetch. */
	private static <T> CompletableFuture<T> cached(Map<String, Cached<T>> cache, String key,
			Supplier<CompletableFuture<T>> fetch) {
		long now = System.currentTimeMillis();
		Cached<T> hit = cache.get(key);
		if (hit != null && hit.expiresAt() > now) {
			return hit.future();
		}
		Cached<T> entry = new Cached<>(fetch.get(), now + CACHE_MILLIS);
		cache.put(key, entry);
		// Don't remember failures, so the next attempt asks the API again.
		entry.future().whenComplete((value, error) -> {
			if (error != null) {
				cache.remove(key, entry);
			}
		});
		return entry.future();
	}

	/** Sends a request; if it times out, tries exactly once more. */
	private static CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request, Timings timings) {
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.exceptionallyCompose(error -> {
				if (unwrap(error) instanceof HttpTimeoutException) {
					timings.retries++;
					return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
				}
				return CompletableFuture.failedFuture(error);
			});
	}

	private static CompletableFuture<String> fetchUuid(String name, Timings timings) {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
			.timeout(TIMEOUT)
			.GET()
			.build();
		return sendWithRetry(request, timings).thenApply(response -> {
			switch (response.statusCode()) {
				case 200 -> {
					// Verified response shape: {"id": "<uuid without dashes>", "name": "<name>"}
					JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
					return json.get("id").getAsString();
				}
				case 404 -> throw new LookupException("player not found");
				case 429 -> throw new LookupException("Mojang rate limited");
				default -> throw new LookupException("Mojang error " + response.statusCode());
			}
		});
	}

	private static CompletableFuture<String> fetchProfiles(String uuid, Timings timings) {
		String apiKey = ModConfig.readApiKey();
		if (apiKey.isEmpty()) {
			return CompletableFuture.failedFuture(
				new LookupException("no API key, add it to " + ModConfig.FILE));
		}
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid))
			.timeout(TIMEOUT)
			.header("API-Key", apiKey)
			.GET()
			.build();
		return sendWithRetry(request, timings).thenApply(response -> {
			switch (response.statusCode()) {
				case 200 -> {
					return response.body();
				}
				case 403 -> throw new LookupException("invalid API key");
				case 429 -> throw new LookupException("rate limited");
				default -> throw new LookupException(
					"Hypixel error " + response.statusCode() + causeOf(response.body()));
			}
		});
	}

	/** Errors from async code arrive wrapped in a CompletionException; this unwraps to the real one. */
	static Throwable unwrap(Throwable error) {
		while (error instanceof CompletionException && error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	/** Hypixel errors look like {"success":false,"cause":"..."} (verified). Returns ": <cause>" or "". */
	private static String causeOf(String body) {
		try {
			JsonElement cause = JsonParser.parseString(body).getAsJsonObject().get("cause");
			return cause != null ? ": " + cause.getAsString() : "";
		} catch (RuntimeException e) {
			return "";
		}
	}
}
