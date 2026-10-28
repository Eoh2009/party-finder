package com.eoh2009.partyfinder.client;

import com.eoh2009.partyfinder.PartyFinder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The user's settings, stored as JSON in the game's config folder.
 * The API key lives ONLY in this file — never in code or git.
 */
public final class ModConfig {
	public static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("party-finder.json");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Gson fills these fields from the JSON keys with the same names.
	private String hypixelApiKey = "";

	/** Creates the config file with an empty key, if it doesn't exist yet. */
	public static void createIfMissing() {
		if (Files.exists(FILE)) {
			return;
		}
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(new ModConfig()));
			PartyFinder.LOGGER.info("Created config file at {}", FILE);
		} catch (IOException e) {
			PartyFinder.LOGGER.error("Could not create config file at {}", FILE, e);
		}
	}

	/**
	 * Reads the API key fresh from the file each time, so you can paste in a key
	 * without restarting the game. Returns "" if no key is set.
	 * Call this off the main thread (it reads a file).
	 */
	public static String readApiKey() throws LookupException {
		try {
			ModConfig config = GSON.fromJson(Files.readString(FILE), ModConfig.class);
			if (config == null || config.hypixelApiKey == null) {
				return "";
			}
			return config.hypixelApiKey.trim();
		} catch (IOException | JsonParseException e) {
			// Never log the file contents here: they contain the key.
			throw new LookupException("can't read config file " + FILE);
		}
	}
}
