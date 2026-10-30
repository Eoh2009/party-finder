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
 *   Gear: Term ✓✪8 ─ Hype ✓✪5 ─ Claymore ✗
 *   Armor: Spirit Mask ✪6 | Necron 2/4 ✪8.5 | Maxor 1/4 ✪10
 *   [Kick]
 * MP (Phase 6) gets added here later.
 */
public final class StatsMessage {
	private static final String SEPARATOR = " ─ ";

	public static Component build(JoinMessage join, DungeonStats s, Gear gear, Accessories accessories) {
		MutableComponent msg = Component.empty();

		// Line 1: who joined + Catacombs level
		msg.append(text(join.name(), ChatFormatting.AQUA))
			.append(text(" (" + join.dungeonClass() + " " + join.classLevel() + ")", ChatFormatting.GRAY))
			.append(separator())
			.append(stat("Cata", String.format(Locale.ROOT, "%.1f", s.cataLevel())));
		// No MP number yet, but say so if we won't be able to work it out.
		if (accessories.error() != null) {
			msg.append(separator())
				.append(text("MP: ", ChatFormatting.GRAY))
				.append(text(accessories.error(), ChatFormatting.RED));
		}

		// Line 2: secrets and blood mob kills
		msg.append("\n")
			.append(stat("Secrets", shortNumber(s.secrets())))
			.append(text(String.format(Locale.ROOT, " (%.1f/run)", s.secretsPerRun()), ChatFormatting.GRAY))
			.append(separator())
			.append(stat("Blood", shortNumber(s.bloodKills())));

		// Line 3: key weapons owned anywhere (inventory, ender chest, backpacks)
		msg.append("\n").append(text("Gear: ", ChatFormatting.GRAY));
		if (gear.weapons() == null) {
			msg.append(text(gear.weaponsError(), ChatFormatting.RED));
		} else {
			for (int i = 0; i < gear.weapons().size(); i++) {
				if (i > 0) {
					msg.append(separator());
				}
				Gear.Weapon w = gear.weapons().get(i);
				msg.append(text(w.label() + " ", ChatFormatting.WHITE));
				if (w.owned()) {
					msg.append(text("✓", ChatFormatting.GREEN)).append(stars(w.stars()));
				} else {
					msg.append(text("✗", ChatFormatting.RED));
				}
			}
		}

		// Line 4: worn armor, grouped by set
		msg.append("\n").append(text("Armor: ", ChatFormatting.GRAY));
		if (gear.armor() == null) {
			msg.append(text(gear.armorError(), ChatFormatting.RED));
		} else if (gear.armor().isEmpty()) {
			msg.append(text("none", ChatFormatting.WHITE));
		} else {
			for (int i = 0; i < gear.armor().size(); i++) {
				if (i > 0) {
					msg.append(text(" | ", ChatFormatting.DARK_GRAY));
				}
				Gear.ArmorPart part = gear.armor().get(i);
				msg.append(text(part.name(), ChatFormatting.WHITE));
				if (part.isSet()) {
					msg.append(text(" " + part.pieces() + "/4", ChatFormatting.GRAY));
				}
				if (part.stars() > 0) {
					msg.append(text(" ", ChatFormatting.WHITE)).append(stars(part.stars()));
				}
			}
		}

		// Line 5: the button
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

	/** "✪8" in gold, or nothing for 0 stars. */
	private static MutableComponent stars(int stars) {
		return stars > 0 ? text("✪" + stars, ChatFormatting.GOLD) : Component.empty();
	}

	/** Armor average: "✪8.5", or "✪9" (not "✪9.0") when it's a whole number. */
	private static MutableComponent stars(double stars) {
		String value = String.format(Locale.ROOT, "%.1f", stars);
		if (value.endsWith(".0")) {
			value = value.substring(0, value.length() - 2);
		}
		return text("✪" + value, ChatFormatting.GOLD);
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
