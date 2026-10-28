package com.eoh2009.partyfinder.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class PartyFinderClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Phase 1: when we join a world/server, print a client-only message (only we see it).
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
			showMessage("Party Finder mod loaded!"));

		// Phase 2: every incoming server message (not player chat) is passed to handleChat.
		// Hypixel sends Party Finder messages this way.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) { // overlay = true means the action bar text, which we ignore
				handleChat(message.getString());
			}
		});

		// Testing helper: /pftest [name] [class] [level] pretends a join message arrived.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(ClientCommands.literal("pftest")
				.executes(ctx -> {
					fakeJoin("o0nai", "Archer", 33);
					return 1;
				})
				.then(ClientCommands.argument("name", StringArgumentType.word())
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
		handleChat("§r§dParty Finder §r§f> §r§b" + name + " §r§ejoined the dungeon group! (§r§b"
			+ dungeonClass + " Level " + level + "§r§e)§r");
	}

	/** Looks at one chat line; if it's a Party Finder join, shows what we extracted. */
	private static void handleChat(String text) {
		JoinMessage join = JoinMessage.parse(text);
		if (join != null) {
			showMessage(join.name() + " (" + join.dungeonClass() + " " + join.classLevel() + ")");
		}
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
