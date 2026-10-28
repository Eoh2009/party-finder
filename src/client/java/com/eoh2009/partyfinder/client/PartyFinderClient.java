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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class PartyFinderClient implements ClientModInitializer {
	// Where /pftest <name> save writes the raw Hypixel response, so we can check real field names (rule 7).
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

		// Testing helpers (all of them also show a timing line):
		//   /pftest                            -> fake join for o0nai
		//   /pftest <name>                     -> fake join for a real player (does a real lookup)
		//   /pftest <name> save                -> same, then saves the raw Hypixel response to a file
		//   /pftest <name> <class> <lvl> [save] -> with a chosen class and level
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(ClientCommands.literal("pftest")
				.executes(ctx -> {
					fakeJoin("o0nai", "Archer", 33, false);
					return 1;
				})
				.then(ClientCommands.argument("name", StringArgumentType.word())
					.executes(ctx -> {
						fakeJoin(StringArgumentType.getString(ctx, "name"), "Archer", 33, false);
						return 1;
					})
					// A "literal" is a fixed word. Brigadier tries it before the <class> argument.
					.then(ClientCommands.literal("save")
						.executes(ctx -> {
							fakeJoin(StringArgumentType.getString(ctx, "name"), "Archer", 33, true);
							return 1;
						}))
					.then(ClientCommands.argument("class", StringArgumentType.word())
						.then(ClientCommands.argument("level", IntegerArgumentType.integer(0, 50))
							.executes(ctx -> {
								fakeJoin(
									StringArgumentType.getString(ctx, "name"),
									StringArgumentType.getString(ctx, "class"),
									IntegerArgumentType.getInteger(ctx, "level"),
									false);
								return 1;
							})
							.then(ClientCommands.literal("save")
								.executes(ctx -> {
									fakeJoin(
										StringArgumentType.getString(ctx, "name"),
										StringArgumentType.getString(ctx, "class"),
										IntegerArgumentType.getInteger(ctx, "level"),
										true);
									return 1;
								})))))));
	}

	/** Builds a fake Hypixel-style message (with color codes, like the real one) and handles it. */
	private static void fakeJoin(String name, String dungeonClass, int level, boolean save) {
		String text = "§r§dParty Finder §r§f> §r§b" + name + " §r§ejoined the dungeon group! (§r§b"
			+ dungeonClass + " Level " + level + "§r§e)§r";
		JoinMessage join = JoinMessage.parse(text);
		if (join == null) {
			showMessage("pftest: '" + name + "' didn't match the join pattern");
			return;
		}
		onJoin(join, true, save);
	}

	/** Looks at one chat line; if it's a Party Finder join, starts a lookup. */
	private static void handleChat(String text) {
		JoinMessage join = JoinMessage.parse(text);
		if (join != null) {
			onJoin(join, false, false);
		}
	}

	/**
	 * Shows the join right away, then looks the player up in the background.
	 * isTest = started by /pftest (shows the timing line); save = also write the raw response to a file.
	 */
	private static void onJoin(JoinMessage join, boolean isTest, boolean save) {
		showMessage(join.name() + " (" + join.dungeonClass() + " " + join.classLevel() + ") - looking up...");
		long start = System.currentTimeMillis();

		// whenComplete runs later, on a background thread, once the lookup succeeds or fails.
		// showMessage then hops back to the game thread to print.
		ProfileLookup.lookup(join.name(), save).whenComplete((result, error) -> {
			long ms = System.currentTimeMillis() - start;
			if (error != null) {
				showMessage(join.name() + ": lookup failed (" + describe(error) + ")");
				return;
			}
			showMessage(StatsMessage.build(join, result.stats(), result.gear()));
			if (isTest) {
				showMessage(Component.literal(timingLine(result.timings(), ms)).withStyle(ChatFormatting.DARK_GRAY));
			}
			// Saving happens last, so it never delays the stats (we're still on a background thread here).
			if (save) {
				saveDebugResponse(join.name(), result.rawJson());
			}
		});
	}

	/** e.g. "Time 5600ms: Mojang 120ms ─ Hypixel 5300ms ─ parse 40ms ─ gear 60ms" */
	private static String timingLine(ProfileLookup.Timings t, long totalMs) {
		String line = String.format(Locale.ROOT, "Time %dms: Mojang %s ─ Hypixel %s ─ parse %s ─ gear %s",
			totalMs, step(t.mojangMs), step(t.hypixelMs), step(t.parseMs), step(t.gearMs));
		if (t.retries > 0) {
			line += " (retried " + t.retries + "x after timeout)";
		}
		return line;
	}

	/** A step that didn't run means its answer came from the cache. */
	private static String step(long ms) {
		return ms < 0 ? "cached" : ms + "ms";
	}

	/** Writes the Hypixel response to a file, nicely indented so it's readable. */
	private static void saveDebugResponse(String name, String json) {
		if (json == null) {
			// Can happen if another lookup of the same player was already running and we shared its request.
			showMessage("Not saved: no fresh response, try again");
			return;
		}
		long start = System.currentTimeMillis();
		try {
			Files.createDirectories(DEBUG_DIR);
			Path file = DEBUG_DIR.resolve(name + ".json");
			String pretty = new GsonBuilder().setPrettyPrinting().create()
				.toJson(JsonParser.parseString(json));
			Files.writeString(file, pretty);
			showMessage("Saved response to " + file + " (" + (System.currentTimeMillis() - start) + "ms)");
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

	/** Prints plain text in the local chat window. Nobody else can see it. */
	private static void showMessage(String text) {
		showMessage(Component.literal(text));
	}

	/** Prints a (possibly colored/clickable) message in the local chat window. */
	private static void showMessage(Component message) {
		Minecraft client = Minecraft.getInstance();
		// Run on the client thread, since chat messages can arrive from other threads.
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendSystemMessage(message);
			}
		});
	}
}
