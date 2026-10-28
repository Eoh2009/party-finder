# Party Finder (Hypixel SkyBlock Dungeons mod)

Client-side Fabric mod. When a player joins my dungeon party through Party Finder, the mod looks up their SkyBlock profile and shows a short stats summary — either as a client-only chat message (only I see it) or in a Discord channel via webhook (toggleable).

Goal: faster and more reliable than Odin's version, with a cleaner layout and gear info.

## About me (the developer)
- Beginner at Java and Minecraft modding. Explain what new code does and why, in plain language.
- Work in small steps. One feature at a time, and make sure it runs before moving on.
- Add short comments to code that isn't obvious.
- Remind me to commit (`git add .`, `git commit -m "..."`, `git push`) after each working step.

## Tech stack
- Minecraft 26.2 (unobfuscated — uses Mojang's official names, no Yarn mappings)
- Fabric Loader 0.20.1, Fabric API 0.161.0+26.2, Fabric Loom 1.19.3, Gradle 9.8.2
- Java 25, written in Java (not Kotlin)
- Package: `com.eoh2009.partyfinder`, split client/common sources. Almost all code belongs in `src/client`.
- Editor: VS Code on Windows (Mac later). Run with `.\gradlew.bat runClient`.
- The dev client is offline (Player102) and cannot join Hypixel. Test chat detection in singleplayer by feeding fake chat messages.

## Hard rules
1. **Never put the Hypixel API key or Discord webhook URL in code or in git.** They live in the user's config file only (Hypixel API policy forbids sharing keys or embedding them in mods).
2. **Read-only mod.** No automated actions. The only action is the clickable `[Kick]` button, which runs `/p kick <name>` only when I click it.
3. **Never do network calls on the render/main thread.** Use async HTTP (`java.net.http.HttpClient` with `sendAsync`) and hop back to the client thread to print.
4. **Never fail silently.** If anything goes wrong, show a short message, e.g. `o0nai: lookup failed (rate limited)` or `Gear: API off`.
5. **Cache lookups** (e.g. 10 min per UUID) and respect Hypixel's rate limit.
6. **Lightweight:** no unnecessary dependencies. Use Gson (ships with Minecraft) for JSON.
7. **Do not guess API field names or NBT paths.** Verify against a real API response first (save one to a test file) before writing parsing code. Verified paths are listed in [docs/api-fields.md](docs/api-fields.md).

## How it works
1. **Detect joins:** listen to incoming game chat (Fabric API client message events). Strip formatting codes, then match:
   `Party Finder > <name> joined the dungeon group! (<Class> Level <n>)`
   Name, class and class level come from this message — no lookup needed for them.
2. **Name → UUID:** Mojang API.
3. **Profile:** Hypixel `/v2/skyblock/profiles?uuid=<uuid>` with header `API-Key`. Use the profile where `selected` is true, then that player's member data.
4. **Parse** only the stats below; ignore the rest.
5. **Output** to client chat and/or Discord webhook, based on settings.

## Output format (chat)
```
o0nai (Archer 33) ─ Cata 36.9 ─ MP 805
Secrets 7.7K (14.0/run) ─ Blood 2.7K
Gear: Term ✓✪8 ─ Hype ✓✪5 ─ Claymore ✗
Armor: Spirit Mask 1/4 ✪6 | Necron 2/4 ✪7 | Maxor 1/4 ✪10
[Kick]
```
Keep lines short; chat is narrow. Shorten names if a line wraps.

### Stat definitions
- **Cata:** Catacombs level from XP (needs the XP→level table), 1 decimal.
- **MP:** Magical Power. Not a direct API field — must be calculated from the accessory bag. Hard; build after the other stats.
- **Secrets:** total secrets found, plus average per run, formatted like `7.7K`.
- **Blood:** blood mob kills, formatted like `2.7K`.
- **Stars:** dungeon stars (1–5) plus master stars (1–5) combined into one number, 0–10.

### Gear line (weapons)
- Fixed list of key weapons, each shown with ✓ + stars if owned, ✗ if not:
  - **Term** = Terminator
  - **Hype** = any of Hyperion, Scylla, Astraea, Valkyrie
  - **Claymore** = Dark Claymore
- Search inventory, ender chest and backpacks.
- If the player owns several matching items (e.g. two Hyperion variants), show the one with the most stars.
- If inventory API is disabled: `Gear: API off`.

### Armor line
- Only armor currently **worn**.
- Group pieces by set: `<Set> <pieces>/4 ✪<stars>`. Pieces not part of a set are shown by name.
- Stars per set = **average** of that set's pieces, rounded to nearest whole number.
- Inventory data is base64 + gzipped NBT and needs decoding.

## Settings
- Toggles: output to chat, output to Discord.
- Values: Hypixel API key, Discord webhook URL.
- Stored in a JSON config file in the game's `config/` folder.
- Start with commands (e.g. `/pf toggle discord`), add a simple GUI screen later.

## Phases
- [x] 0. Setup: Java, Git, GitHub, Fabric template runs
- [x] 1. Client-only chat message on game join (hello world)
- [x] 2. Detect Party Finder join messages and extract name/class/level
- [x] 3. Mojang + Hypixel lookups (async, cached), basic stats: Cata, Secrets, Blood
- [ ] 4. Formatted chat output + [Kick] button
- [ ] 5. Gear + armor (NBT decoding)
- [ ] 6. Magical Power
- [ ] 7. Discord webhook output
- [ ] 8. Config file, commands, simple GUI
- [ ] Later: PB times for the party's floor

## Not in scope (for now)
- PB times, [Profile] button, auto-kick or any other automation, public release.
