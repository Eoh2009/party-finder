package com.eoh2009.partyfinder.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Looks up a player: name -> UUID (Mojang), then UUID -> SkyBlock profiles (Hypixel).
 * Everything is async: methods return a CompletableFuture (a "result that arrives later")
 * instead of blocking the game while waiting for the internet.
 */
public final class ProfileLookup {
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** What a successful lookup gives back. profilesJson is Hypixel's raw response text. */
	public record Result(String name, String uuid, String profilesJson) {}

	// A cache entry stores the pending/finished request plus when it expires.
	// Storing the future (not just the answer) means two lookups of the same player
	// at the same moment share one request instead of sending two.
	private record Cached<T>(CompletableFuture<T> future, long expiresAt) {}

	private static final Map<String, Cached<String>> UUID_CACHE = new ConcurrentHashMap<>();     // key: lowercase name
	private static final Map<String, Cached<String>> PROFILES_CACHE = new ConcurrentHashMap<>(); // key: uuid

	public static CompletableFuture<Result> lookup(String name) {
		// Same rule as Minecraft names. Also stops odd characters from breaking the URL.
		if (!name.matches("\\w{1,16}")) {
			return CompletableFuture.failedFuture(new LookupException("invalid name"));
		}
		// "Async" = continue on a background thread, never the game thread
		// (fetchProfiles reads the config file).
		return getUuid(name).thenComposeAsync(uuid ->
			getProfiles(uuid).thenApply(json -> new Result(name, uuid, json)));
	}

	private static CompletableFuture<String> getUuid(String name) {
		return cached(UUID_CACHE, name.toLowerCase(Locale.ROOT), () -> fetchUuid(name));
	}

	private static CompletableFuture<String> getProfiles(String uuid) {
		return cached(PROFILES_CACHE, uuid, () -> fetchProfiles(uuid));
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

	private static CompletableFuture<String> fetchUuid(String name) {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
			.timeout(TIMEOUT)
			.GET()
			.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
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

	private static CompletableFuture<String> fetchProfiles(String uuid) {
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
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
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
