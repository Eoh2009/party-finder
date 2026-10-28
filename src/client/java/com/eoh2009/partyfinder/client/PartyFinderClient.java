package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

public class PartyFinderClient implements ClientModInitializer {
	// Where /pftest saves the raw Hypixel response, so we can check real field names (rule 7).
	private static final Path DEBUG_DIR = FabricLoader.getInstance().getGameDir().resolve("party-finder-debug");

	// Static = shared for the whole game session, so it stays true across server joins.
	private static boolean loadedMessageShown = false;

	@Override
	public void onInitializeClient() {
		ModConfig.createIfMissing();

		// Phase 1: on the first world/server join of this game session, print a client-only message.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (!loadedMessageShown) {
				loadedMessageShown = true;
				showMessage("Party Finder mod loaded!");
			}
		});

		// Phase 2: every incoming server message (not player chat) is passed to handleChat.
		// Hypixel sends Party Finder messages this way.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) { // overlay = true means the action bar text, which we ignore
				handleChat(message.getString());
			}
		});

		// Testing helpers:
		//   /pftest                       -> fake join for o0nai
		//   /pftest <name>                -> fake join for a real player (does a real lookup)
		//   /pftest <name> <class> <lvl>  -> same, with a chosen class and level
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(ClientCommands.literal("pftest")
				.executes(ctx -> {
					fakeJoin("o0nai", "Archer", 33);
					return 1;
				})
				.then(ClientCommands.argument("name", StringArgumentType.word())
					.executes(ctx -> {
						fakeJoin(StringArgumentType.getString(ctx, "name"), "Archer", 33);
						return 1;
					})
					.then(ClientCommands.argument("class", StringArgumentType.word())
						.then(ClientCommands.argument("level", IntegerArgumentType.integer(0, 50))
							.executes(ctx -> {
								fakeJoin(
									StringArgumentType.getString(ctx, "name"),
									StringArgumentType.getString(ctx, "class"),
									IntegerArgumentType.getInteger(ctx, "level"));
								return 1;
							}))))));
	}

	/** Builds a fake Hypixel-style message (with color codes, like the real one) and handles it. */
	private static void fakeJoin(String name, String dungeonClass, int level) {
		String text = "§r§dParty Finder §r§f> §r§b" + name + " §r§ejoined the dungeon group! (§r§b"
			+ dungeonClass + " Level " + level + "§r§e)§r";
		JoinMessage join = JoinMessage.parse(text);
		if (join == null) {
			showMessage("pftest: '" + name + "' didn't match the join pattern");
			return;
		}
		onJoin(join, true);
	}

	/** Looks at one chat line; if it's a Party Finder join, starts a lookup. */
	private static void handleChat(String text) {
		JoinMessage join = JoinMessage.parse(text);
		if (join != null) {
			onJoin(join, false);
		}
	}

	/** Shows the join right away, then looks the player up in the background. */
	private static void onJoin(JoinMessage join, boolean saveResponse) {
		showMessage(join.name() + " (" + join.dungeonClass() + " " + join.classLevel() + ") - looking up...");
		long start = System.currentTimeMillis();

		// Only /pftest saves the raw response (and only when it's freshly downloaded, not cached).
		Consumer<String> rawListener = saveResponse ? json -> saveDebugResponse(join.name(), json) : null;

		// whenComplete runs later, on a background thread, once the lookup succeeds or fails.
		// showMessage then hops back to the game thread to print.
		ProfileLookup.lookup(join.name(), rawListener).whenComplete((result, error) -> {
			long ms = System.currentTimeMillis() - start;
			if (error != null) {
				showMessage(join.name() + ": lookup failed (" + describe(error) + ")");
				return;
			}
			// Simple output for now; the final layout comes in Phase 4.
			DungeonStats s = result.stats();
			showMessage(String.format(Locale.ROOT, "%s: Cata %.1f ─ Secrets %s (%.1f/run) ─ Blood %s",
				join.name(), s.cataLevel(), shortNumber(s.secrets()), s.secretsPerRun(),
				shortNumber(s.bloodKills())));
			showMessage(timingLine(result.timings(), ms));
		});
	}

	/** e.g. "Time 5.6s: Mojang 120ms ─ Hypixel 5300ms ─ parse 40ms ─ save 900ms" */
	private static String timingLine(ProfileLookup.Timings t, long totalMs) {
		String line = String.format(Locale.ROOT, "Time %dms: Mojang %s ─ Hypixel %s ─ parse %s",
			totalMs, step(t.mojangMs), step(t.hypixelMs), step(t.parseMs));
		if (t.saveMs >= 0) {
			line += " ─ save " + t.saveMs + "ms";
		}
		if (t.retries > 0) {
			line += " (retried " + t.retries + "x after timeout)";
		}
		return line;
	}

	/** A step that didn't run means its answer came from the cache. */
	private static String step(long ms) {
		return ms < 0 ? "cached" : ms + "ms";
	}

	/** Shortens big numbers: 950 -> "950", 7700 -> "7.7K", 2500000 -> "2.5M". */
	static String shortNumber(long n) {
		if (n < 1000) {
			return Long.toString(n);
		}
		if (n < 1_000_000) {
			return String.format(Locale.ROOT, "%.1fK", n / 1000.0);
		}
		return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
	}

	/** Writes the Hypixel response to a file, nicely indented so it's readable. */
	private static void saveDebugResponse(String name, String json) {
		try {
			Files.createDirectories(DEBUG_DIR);
			Path file = DEBUG_DIR.resolve(name + ".json");
			String pretty = new GsonBuilder().setPrettyPrinting().create()
				.toJson(JsonParser.parseString(json));
			Files.writeString(file, pretty);
			showMessage("Saved response to " + file);
		} catch (IOException | RuntimeException e) {
			PartyFinder.LOGGER.error("Could not save debug response", e);
			showMessage("Could not save response (" + e.getMessage() + ")");
		}
	}

	/** Turns an error into a short reason for chat. */
	private static String describe(Throwable error) {
		error = ProfileLookup.unwrap(error);
		if (error instanceof LookupException) {
			return error.getMessage();
		}
		if (error instanceof HttpTimeoutException) {
			return "timed out";
		}
		if (error instanceof IOException) {
			return "network error";
		}
		PartyFinder.LOGGER.error("Unexpected lookup error", error);
		return "unexpected error, see log";
	}

	/** Prints a line in the local chat window. Nobody else can see it. */
	private static void showMessage(String text) {
		Minecraft client = Minecraft.getInstance();
		// Run on the client thread, since chat messages can arrive from other threads.
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendSystemMessage(Component.literal(text));
			}
		});
	}
}
