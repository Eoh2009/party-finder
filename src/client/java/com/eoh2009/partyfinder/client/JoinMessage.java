package com.eoh2009.partyfinder.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pieces of information pulled out of a Party Finder join message.
 * A "record" is a Java shortcut for a small class that just holds values.
 */
public record JoinMessage(String name, String dungeonClass, int classLevel) {

	// Matches: Party Finder > o0nai joined the dungeon group! (Archer Level 33)
	// The parts in ( ) are "capture groups": 1 = name, 2 = class, 3 = level.
	private static final Pattern PATTERN = Pattern.compile(
		"^Party Finder > (\\w{1,16}) joined the dungeon group! \\((\\w+) Level (\\d+)\\)$");

	// Matches Minecraft's color/format codes: the § sign followed by one character.
	private static final Pattern FORMAT_CODES = Pattern.compile("§.");

	/** Returns the parsed message, or null if the text is not a Party Finder join. */
	public static JoinMessage parse(String rawText) {
		String text = FORMAT_CODES.matcher(rawText).replaceAll("").trim();
		Matcher m = PATTERN.matcher(text);
		if (!m.matches()) {
			return null;
		}
		return new JoinMessage(m.group(1), m.group(2), Integer.parseInt(m.group(3)));
	}
}
