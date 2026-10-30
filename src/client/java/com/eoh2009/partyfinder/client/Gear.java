package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Gear line (key weapons the player owns) and the Armor line (what they're wearing).
 * Both come from the inventory "blobs": base64 text -> gzip -> NBT (Minecraft's item data format).
 * Paths and item ids are verified in docs/api-fields.md.
 *
 * A list is null when that line couldn't be made; the matching error says why ("API off", "read error").
 */
public record Gear(List<Weapon> weapons, String weaponsError, List<ArmorPart> armor, String armorError) {

	/** One entry on the Gear line, e.g. "Term" with 8 stars. stars = -1 means not owned. */
	public record Weapon(String label, int stars) {
		public boolean owned() {
			return stars >= 0;
		}
	}

	/** One entry on the Armor line: a set ("Necron", 2 pieces) or a single item ("Spirit Mask"). */
	public record ArmorPart(String name, int pieces, double stars, boolean isSet) {}

	// Gear line: label -> the item ids that count for it. LinkedHashMap keeps this order in chat.
	private static final Map<String, Set<String>> WEAPONS = new LinkedHashMap<>();
	static {
		WEAPONS.put("Term", Set.of("TERMINATOR"));
		WEAPONS.put("Hype", Set.of("HYPERION", "SCYLLA", "ASTRAEA", "VALKYRIE"));
		WEAPONS.put("Claymore", Set.of("DARK_CLAYMORE"));
	}

	// Armor ids look like <SET>_HELMET etc. These sets get their in-game name instead of the id.
	private static final Map<String, String> SET_NAMES = Map.of(
		"POWER_WITHER", "Necron",
		"SPEED_WITHER", "Maxor",
		"WISE_WITHER", "Storm",
		"TANK_WITHER", "Goldor");
	private static final Pattern ARMOR_PIECE = Pattern.compile("(.+)_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)");

	/** Builds both lines from one player's member data. Never throws: problems end up in the error fields. */
	public static Gear parse(JsonObject member) {
		JsonObject inv = DungeonStats.obj(member, "inventory");

		List<Weapon> weapons = null;
		String weaponsError = null;
		if (DungeonStats.obj(inv, "inv_contents") == null) {
			weaponsError = "API off";
		} else {
			try {
				weapons = findWeapons(inv);
			} catch (IOException | RuntimeException e) {
				PartyFinder.LOGGER.error("Could not read inventory items", e);
				weaponsError = "read error";
			}
		}

		List<ArmorPart> armor = null;
		String armorError = null;
		JsonObject armorBlob = DungeonStats.obj(inv, "inv_armor");
		if (armorBlob == null) {
			armorError = "API off";
		} else {
			try {
				armor = groupArmor(decode(armorBlob));
			} catch (IOException | RuntimeException e) {
				PartyFinder.LOGGER.error("Could not read armor", e);
				armorError = "read error";
			}
		}
		return new Gear(weapons, weaponsError, armor, armorError);
	}

	/** Looks through inventory, ender chest and every backpack; keeps the most stars per label. */
	private static List<Weapon> findWeapons(JsonObject inv) throws IOException {
		List<JsonObject> blobs = new ArrayList<>();
		blobs.add(DungeonStats.obj(inv, "inv_contents"));
		blobs.add(DungeonStats.obj(inv, "ender_chest_contents"));
		// backpack_contents is { "0": blob, "1": blob, ... }, one per backpack
		JsonObject backpacks = DungeonStats.obj(inv, "backpack_contents");
		if (backpacks != null) {
			for (Map.Entry<String, JsonElement> e : backpacks.entrySet()) {
				if (e.getValue().isJsonObject()) {
					blobs.add(e.getValue().getAsJsonObject());
				}
			}
		}

		Map<String, Integer> best = new LinkedHashMap<>(); // label -> most stars seen so far
		for (JsonObject blob : blobs) {
			if (blob == null) {
				continue;
			}
			ListTag items = decode(blob);
			for (int slot = 0; slot < items.size(); slot++) {
				CompoundTag extra = extraAttributes(items.getCompoundOrEmpty(slot));
				String label = weaponLabel(extra.getStringOr("id", ""));
				if (label != null) {
					best.merge(label, stars(extra), Math::max);
				}
			}
		}

		List<Weapon> result = new ArrayList<>();
		for (String label : WEAPONS.keySet()) {
			result.add(new Weapon(label, best.getOrDefault(label, -1)));
		}
		return result;
	}

	private static String weaponLabel(String id) {
		for (Map.Entry<String, Set<String>> e : WEAPONS.entrySet()) {
			if (e.getValue().contains(id)) {
				return e.getKey();
			}
		}
		return null;
	}

	/** Groups worn pieces by set. Stars per group = average of its pieces (can be e.g. 8.5). */
	private static List<ArmorPart> groupArmor(ListTag items) {
		// Group name -> stars of each piece in it. LinkedHashMap keeps the order we add them in.
		Map<String, List<Integer>> groups = new LinkedHashMap<>();
		Map<String, Boolean> isSet = new LinkedHashMap<>();
		// Slots are 0 = boots ... 3 = helmet. Go backwards so the line reads helmet first.
		for (int slot = items.size() - 1; slot >= 0; slot--) {
			CompoundTag extra = extraAttributes(items.getCompoundOrEmpty(slot));
			String id = extra.getStringOr("id", "");
			if (id.isEmpty()) {
				continue; // empty slot
			}
			Matcher m = ARMOR_PIECE.matcher(stripStarred(id));
			String name = m.matches() ? setName(m.group(1)) : prettify(stripStarred(id));
			groups.computeIfAbsent(name, k -> new ArrayList<>()).add(stars(extra));
			isSet.put(name, m.matches());
		}

		List<ArmorPart> result = new ArrayList<>();
		for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
			List<Integer> stars = e.getValue();
			double average = stars.stream().mapToInt(Integer::intValue).average().orElse(0);
			result.add(new ArmorPart(e.getKey(), stars.size(), average, isSet.get(e.getKey())));
		}
		return result;
	}

	/** Turns one {data, type} blob into its list of item slots. Accessories uses this too. */
	static ListTag decode(JsonObject blob) throws IOException {
		JsonElement data = blob.get("data");
		if (data == null || data.getAsString().isEmpty()) {
			return new ListTag();
		}
		byte[] gzipped = Base64.getDecoder().decode(data.getAsString());
		// readCompressed un-gzips and reads the NBT. The accounter caps memory use (2 MB per blob).
		CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(gzipped), NbtAccounter.defaultQuota());
		return root.getListOrEmpty("i");
	}

	/** SkyBlock's own item data lives in tag.ExtraAttributes. Empty for empty slots. */
	static CompoundTag extraAttributes(CompoundTag item) {
		return item.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes");
	}

	/** Newer items: upgrade_level (0-10, dungeon + master stars). Older items: dungeon_item_level. */
	private static int stars(CompoundTag extra) {
		return extra.getInt("upgrade_level")
			.or(() -> extra.getInt("dungeon_item_level"))
			.orElse(0);
	}

	/** STARRED_SPIRIT_MASK is just the starred version of SPIRIT_MASK; same name in game. */
	private static String stripStarred(String id) {
		return id.startsWith("STARRED_") ? id.substring("STARRED_".length()) : id;
	}

	private static String setName(String setId) {
		String known = SET_NAMES.get(setId);
		return known != null ? known : prettify(setId);
	}

	/** SHADOW_ASSASSIN -> "Shadow Assassin" */
	private static String prettify(String id) {
		StringBuilder sb = new StringBuilder();
		for (String word : id.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}
}
