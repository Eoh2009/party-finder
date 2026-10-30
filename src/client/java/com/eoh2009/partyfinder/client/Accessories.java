package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The accessory bag: each accessory's item id and rarity. Magical Power gets calculated from this later.
 * Paths are verified in docs/api-fields.md.
 *
 * items is null when the bag couldn't be read; error then says why ("API off", "read error").
 */
public record Accessories(List<Accessory> items, String error) {

	public record Accessory(String id, String rarity) {}

	static final String UNKNOWN_RARITY = "UNKNOWN";

	// The last lore line, with color codes removed, looks like one of these:
	//   RARE ACCESSORY
	//   LEGENDARY DUNGEON ACCESSORY
	//   a MYTHIC ACCESSORY a          (recombobulated: the "a"s are the scrambled letters on each side)
	//   a VERY SPECIAL HATCESSORY a
	// Capture group 1 is the rarity. It's all capitals, so the lowercase "a" is never part of it.
	private static final Pattern RARITY = Pattern.compile("([A-Z][A-Z ]*?) (?:DUNGEON )?(?:ACCESSORY|HATCESSORY)\\b");

	// Highest first, for the debug line. Any other rarity comes after these.
	private static final List<String> RARITY_ORDER = List.of(
		"VERY SPECIAL", "SPECIAL", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON");

	/** Reads the bag from one player's member data. Never throws: problems end up in error. */
	public static Accessories parse(JsonObject member) {
		JsonObject bags = DungeonStats.obj(DungeonStats.obj(member, "inventory"), "bag_contents");
		JsonObject blob = DungeonStats.obj(bags, "talisman_bag");
		if (blob == null) {
			return new Accessories(null, "API off");
		}
		try {
			return new Accessories(readItems(Gear.decode(blob)), null);
		} catch (IOException | RuntimeException e) {
			PartyFinder.LOGGER.error("Could not read accessory bag", e);
			return new Accessories(null, "read error");
		}
	}

	private static List<Accessory> readItems(ListTag slots) {
		List<Accessory> result = new ArrayList<>();
		for (int slot = 0; slot < slots.size(); slot++) {
			CompoundTag item = slots.getCompoundOrEmpty(slot);
			String id = Gear.extraAttributes(item).getStringOr("id", "");
			if (!id.isEmpty()) { // empty bag slots have no id
				result.add(new Accessory(id, rarity(item, id)));
			}
		}
		return result;
	}

	/** Reads the rarity from the last lore line, which already includes any recombobulator upgrade. */
	private static String rarity(CompoundTag item, String id) {
		ListTag lore = item.getCompoundOrEmpty("tag").getCompoundOrEmpty("display").getListOrEmpty("Lore");
		String lastLine = lore.isEmpty() ? "" : lore.getStringOr(lore.size() - 1, "");
		Matcher m = RARITY.matcher(ChatFormatting.stripFormatting(lastLine));
		if (m.find()) {
			return m.group(1);
		}
		PartyFinder.LOGGER.warn("No rarity found for {} in lore line: {}", id, lastLine);
		return UNKNOWN_RARITY;
	}

	/** TEMPORARY debug line for /pftest, e.g. "48 accessories: 12 LEGENDARY, 20 EPIC, 16 RARE" */
	public String debugLine() {
		if (items == null) {
			return "Accessories: " + error;
		}
		// TreeMap keeps its keys sorted: known rarities in RARITY_ORDER, then anything else.
		Map<String, Integer> counts = new TreeMap<>(Comparator
			.comparingInt(Accessories::rarityRank)
			.thenComparing(Comparator.naturalOrder()));
		for (Accessory a : items) {
			counts.merge(a.rarity(), 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((rarity, count) -> parts.add(count + " " + rarity));
		return items.size() + " accessories: " + String.join(", ", parts);
	}

	private static int rarityRank(String rarity) {
		int rank = RARITY_ORDER.indexOf(rarity);
		return rank >= 0 ? rank : RARITY_ORDER.size();
	}
}
