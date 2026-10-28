package com.eoh2009.partyfinder.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * Builds the chat summary for one player, e.g.
 *   o0nai (Archer 33) ─ Cata 36.9
 *   Secrets 7.7K (14.0/run) ─ Blood 2.7K
 *   [Kick]
 * MP (Phase 6) and the Gear/Armor lines (Phase 5) get added here later.
 */
public final class StatsMessage {
	private static final String SEPARATOR = " ─ ";

	public static Component build(JoinMessage join, DungeonStats s) {
		MutableComponent msg = Component.empty();

		// Line 1: who joined + Catacombs level
		msg.append(text(join.name(), ChatFormatting.AQUA))
			.append(text(" (" + join.dungeonClass() + " " + join.classLevel() + ")", ChatFormatting.GRAY))
			.append(separator())
			.append(stat("Cata", String.format(Locale.ROOT, "%.1f", s.cataLevel())));

		// Line 2: secrets and blood mob kills
		msg.append("\n")
			.append(stat("Secrets", shortNumber(s.secrets())))
			.append(text(String.format(Locale.ROOT, " (%.1f/run)", s.secretsPerRun()), ChatFormatting.GRAY))
			.append(separator())
			.append(stat("Blood", shortNumber(s.bloodKills())));

		// Line 3: the button
		msg.append("\n").append(kickButton(join.name()));
		return msg;
	}

	/**
	 * A red [Kick] that runs /p kick <name> only when clicked. Nothing runs by itself.
	 * The name is safe to put in a command: JoinMessage only accepts letters, digits and _.
	 */
	private static Component kickButton(String name) {
		String command = "/p kick " + name;
		return text("[Kick]", ChatFormatting.RED).withStyle(ChatFormatting.BOLD).withStyle(style -> style
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to run " + command))));
	}

	/** "Cata 36.9" with a gray label and a white value. */
	private static MutableComponent stat(String label, String value) {
		return text(label + " ", ChatFormatting.GRAY).append(text(value, ChatFormatting.WHITE));
	}

	private static MutableComponent separator() {
		return text(SEPARATOR, ChatFormatting.DARK_GRAY);
	}

	private static MutableComponent text(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(color);
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
}
