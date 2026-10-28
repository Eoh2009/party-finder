package com.eoh2009.partyfinder.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * The few stats we care about, pulled out of Hypixel's big profiles response.
 * Field paths are documented in docs/api-fields.md.
 */
public record DungeonStats(double cataLevel, long secrets, long runs, long bloodKills) {

	// XP needed for each Catacombs level 1..50 (from the NEU repo's leveling.json).
	private static final long[] CATA_XP = {
		50, 75, 110, 160, 230, 330, 470, 670, 950, 1340,
		1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000, 27600, 38000,
		52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000,
		1065000, 1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000,
		15000000, 19000000, 24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000};
	// Past level 50, every level costs the same.
	private static final long XP_PER_LEVEL_AFTER_50 = 200_000_000;

	public double secretsPerRun() {
		return runs == 0 ? 0 : (double) secrets / runs;
	}

	/** Parses the raw /v2/skyblock/profiles response for the player with this UUID (no dashes). */
	public static DungeonStats parse(String profilesJson, String uuid) {
		JsonObject root = JsonParser.parseString(profilesJson).getAsJsonObject();
		JsonObject member = findSelectedMember(root, uuid);

		JsonObject dungeons = obj(member, "dungeons");
		JsonObject types = obj(dungeons, "dungeon_types");
		JsonObject cata = obj(types, "catacombs");
		JsonObject master = obj(types, "master_catacombs");

		double xp = num(cata, "experience");
		long secrets = (long) num(dungeons, "secrets");
		// Don't use tier_completions.total: it's sometimes missing or doesn't match the floors.
		long runs = sumFloors(obj(cata, "tier_completions")) + sumFloors(obj(master, "tier_completions"));
		// Exactly this one key (not watcher_summon_undead_<n> or master_...). Checked against
		// the real blood counts of two players; see docs/api-fields.md.
		long blood = (long) num(obj(obj(member, "player_stats"), "kills"), "watcher_summon_undead");

		return new DungeonStats(cataLevel(xp), secrets, runs, blood);
	}

	/** Finds the profile marked "selected", then this player's data inside it. */
	private static JsonObject findSelectedMember(JsonObject root, String uuid) {
		JsonElement profiles = root.get("profiles");
		// "profiles" is null in the JSON for players who never played SkyBlock.
		if (profiles == null || !profiles.isJsonArray()) {
			throw new LookupException("no SkyBlock profile");
		}
		for (JsonElement p : (JsonArray) profiles) {
			JsonObject profile = p.getAsJsonObject();
			JsonElement selected = profile.get("selected");
			if (selected != null && selected.getAsBoolean()) {
				JsonObject member = obj(obj(profile, "members"), uuid);
				if (member == null) {
					throw new LookupException("player missing from profile");
				}
				return member;
			}
		}
		throw new LookupException("no selected profile");
	}

	/** Adds up runs on the numbered floors, skipping "total". */
	private static long sumFloors(JsonObject tierCompletions) {
		if (tierCompletions == null) {
			return 0;
		}
		long sum = 0;
		for (Map.Entry<String, JsonElement> e : tierCompletions.entrySet()) {
			if (e.getKey().matches("\\d+")) {
				sum += (long) e.getValue().getAsDouble();
			}
		}
		return sum;
	}

	/** Turns total XP into a level with a fraction, e.g. 36.9 = level 36 and 90% to 37. */
	static double cataLevel(double xp) {
		int level = 0;
		while (true) {
			long needed = level < CATA_XP.length ? CATA_XP[level] : XP_PER_LEVEL_AFTER_50;
			if (xp < needed) {
				return level + xp / needed;
			}
			xp -= needed;
			level++;
		}
	}

	// Small helpers that return null / 0 instead of crashing when a field is missing
	// (e.g. a player who has never done dungeons has no "dungeons" data).

	private static JsonObject obj(JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement e = parent.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static double num(JsonObject parent, String key) {
		if (parent == null) {
			return 0;
		}
		JsonElement e = parent.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
	}
}
