package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The accessory bag: each accessory's item id and rarity. Magical Power gets calculated from this later.
 * Paths are verified in docs/api-fields.md.
 *
 * active = what counts toward MP. removed = lower tiers and duplicates, which don't.
 * Both are null when the bag couldn't be read; error then says why ("API off", "read error", "data error").
 */
public record Accessories(List<Accessory> active, List<Accessory> removed, String error) {

	public record Accessory(String id, String rarity) {}

	/** Where an id sits: which family it's in, and how high up (0 = lowest tier). */
	private record FamilySpot(String family, int tier) {}

	static final String UNKNOWN_RARITY = "UNKNOWN";

	private static final String FAMILIES_FILE = "/party-finder/accessory_families.json";

	// id -> family spot. Read once, from inside the mod (no download). null if the file couldn't be read.
	private static final Map<String, FamilySpot> FAMILIES = loadFamilies();

	// The last lore line, with color codes removed, looks like one of these:
	//   RARE ACCESSORY
	//   LEGENDARY DUNGEON ACCESSORY
	//   a MYTHIC ACCESSORY a          (recombobulated: the "a"s are the scrambled letters on each side)
	//   a VERY SPECIAL HATCESSORY a
	// Capture group 1 is the rarity. It's all capitals, so the lowercase "a" is never part of it.
	private static final Pattern RARITY = Pattern.compile("([A-Z][A-Z ]*?) (?:DUNGEON )?(?:ACCESSORY|HATCESSORY)");

	// Highest first, to pick a winner on the same tier. Any other rarity comes after these.
	private static final List<String> RARITY_ORDER = List.of(
		"VERY SPECIAL", "SPECIAL", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON");

	/** Reads the bag from one player's member data. Never throws: problems end up in error. */
	public static Accessories parse(JsonObject member) {
		JsonObject bags = DungeonStats.obj(DungeonStats.obj(member, "inventory"), "bag_contents");
		JsonObject blob = DungeonStats.obj(bags, "talisman_bag");
		if (blob == null) {
			return failed("API off");
		}
		if (FAMILIES == null) {
			return failed("data error");
		}
		try {
			return sortOut(readItems(Gear.decode(blob)));
		} catch (IOException | RuntimeException e) {
			PartyFinder.LOGGER.error("Could not read accessory bag", e);
			return failed("read error");
		}
	}

	private static Accessories failed(String error) {
		return new Accessories(null, null, error);
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

	/**
	 * Splits the bag into what counts toward MP and what doesn't. Each family keeps one winner.
	 * An id with no family is its own family of one, so the same id twice also keeps just one.
	 */
	private static Accessories sortOut(List<Accessory> items) {
		Map<String, Accessory> winners = new LinkedHashMap<>(); // family -> best so far, in bag order
		List<Accessory> removed = new ArrayList<>();
		for (Accessory a : items) {
			String family = spot(a).family();
			Accessory best = winners.get(family);
			if (best == null) {
				winners.put(family, a);
			} else if (beats(a, best)) {
				winners.put(family, a);
				removed.add(best);
			} else {
				removed.add(a);
			}
		}
		return new Accessories(new ArrayList<>(winners.values()), removed, null);
	}

	/** Higher tier wins. On the same tier the higher rarity wins, and a full tie keeps the one we already have. */
	private static boolean beats(Accessory a, Accessory best) {
		int tierA = spot(a).tier();
		int tierBest = spot(best).tier();
		if (tierA != tierBest) {
			return tierA > tierBest;
		}
		return rarityRank(a.rarity()) < rarityRank(best.rarity()); // rank 0 is the highest rarity
	}

	private static FamilySpot spot(Accessory a) {
		return FAMILIES.getOrDefault(a.id(), new FamilySpot(a.id(), 0));
	}

	/** Reads the bundled file: families -> tiers (lowest first) -> ids. Returns null if that fails. */
	private static Map<String, FamilySpot> loadFamilies() {
		try (InputStream in = Accessories.class.getResourceAsStream(FAMILIES_FILE)) {
			if (in == null) {
				throw new IOException("missing from the mod");
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, FamilySpot> result = new HashMap<>();
			for (JsonElement family : root.getAsJsonArray("families")) {
				JsonArray tiers = family.getAsJsonArray();
				// The family is named after its first id. Any unique name would do.
				String name = tiers.get(0).getAsJsonArray().get(0).getAsString();
				for (int tier = 0; tier < tiers.size(); tier++) {
					for (JsonElement id : tiers.get(tier).getAsJsonArray()) {
						result.put(id.getAsString(), new FamilySpot(name, tier));
					}
				}
			}
			return result;
		} catch (IOException | RuntimeException e) {
			PartyFinder.LOGGER.error("Could not read {}", FAMILIES_FILE, e);
			return null;
		}
	}

	/** TEMPORARY debug line for /pftest, e.g. "109 accessories, 107 active, removed: ZOMBIE_RING, CENTURY_TALISMAN" */
	public String debugLine() {
		if (active == null) {
			return "Accessories: " + error;
		}
		List<String> ids = new ArrayList<>();
		for (Accessory a : removed) {
			ids.add(a.id());
		}
		return (active.size() + removed.size()) + " accessories, " + active.size() + " active, removed: "
			+ (ids.isEmpty() ? "none" : String.join(", ", ids));
	}

	private static int rarityRank(String rarity) {
		int rank = RARITY_ORDER.indexOf(rarity);
		return rank >= 0 ? rank : RARITY_ORDER.size();
	}
}
