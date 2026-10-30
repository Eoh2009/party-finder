# API field paths (verified against real responses)

Paths only, no real values. `M` = `profiles[].members.<uuid>`.

## Mojang `GET https://api.mojang.com/users/profiles/minecraft/<name>`
| Path | Type | Notes |
|---|---|---|
| `id` | string | UUID **without dashes** |
| `name` | string | correctly-cased name |
| HTTP 404 | | unknown name |

## Hypixel `GET /v2/skyblock/profiles?uuid=<uuid>` (header `API-Key`)
| Path | Type | Notes |
|---|---|---|
| `success` | bool | |
| `cause` | string | only on errors (403 = `Invalid API key`) |
| `profiles` | array | |
| `profiles[].selected` | bool | use the profile where this is `true` |
| `profiles[].members` | object | keyed by UUID **without dashes** (same as Mojang `id`); co-op profiles have several |

### Dungeons
| Path | Type | Notes |
|---|---|---|
| `M.dungeons.dungeon_types.catacombs.experience` | number | Catacombs XP (master has no own XP field) |
| `M.dungeons.dungeon_types.catacombs.tier_completions.<n>` | number | runs per floor; `0` = Entrance |
| `M.dungeons.dungeon_types.master_catacombs.tier_completions.<n>` | number | runs per master floor |
| `...tier_completions.total` | number | **unreliable**: sometimes missing, sometimes ≠ sum of floors. Sum `<n>` keys instead |
| `M.dungeons.dungeon_types.<type>.highest_tier_completed` | number | |
| `M.dungeons.dungeon_types.<type>.fastest_time_s_plus` | object | for later (PB times) |
| `M.dungeons.secrets` | number | total secrets found |
| `M.dungeons.selected_dungeon_class` | string | |
| `M.dungeons.player_classes.<class>.experience` | number | archer, berserk, healer, mage, tank |
| `M.player_stats.kills.watcher_summon_undead` | number | **Blood mob kills.** This exact key only; matched known values for 2 players |

Blood: candidates that did **not** match, so don't use them:
- `M.dungeons.dungeon_types.catacombs.watcher_kills.<n>` / `.total` (number), and the same under `master_catacombs` (may be an empty object)
- `M.player_stats.kills.watcher_summon_undead_<n>`, `M.player_stats.kills.master_watcher_summon_undead*` (either added in or alone)
- every `M.player_stats.kills.watcher*` + `master_watcher*` key added together
- `M.bestiary.kills.watcher_summon_undead_<n>` (number): bestiary has its own separate counters

### Items (each is `{ data: string, type: number }`; `data` = base64 + gzipped NBT)
| Path | Notes |
|---|---|
| `M.inventory.inv_armor` | worn armor |
| `M.inventory.equipment_contents` | equipment slots |
| `M.inventory.inv_contents` | main inventory |
| `M.inventory.ender_chest_contents` | |
| `M.inventory.backpack_contents.<n>` | one entry per backpack |
| `M.inventory.personal_vault_contents` | |
| `M.inventory.wardrobe_equipped_slot` | number, not an item blob |
| `M.inventory.bag_contents.talisman_bag` | accessory bag (for MP). Missing = "API off", same as `inv_armor` (seen on co-op members with only `sacks_bag`, or no `inventory`) |
| `M.inventory` (whole object) | missing for some co-op members; others have **only** `bag_contents`. So treat a missing `inv_armor` / `inv_contents` as "API off" (checked per field) |

An empty `data` string was not seen in our files; the code still treats it as "no items".

### Inside an item blob (verified by decoding real blobs with Minecraft's `NbtIo.readCompressed`)
`data` → base64 decode → gzip → NBT compound. Root:
| NBT path | Type | Notes |
|---|---|---|
| `i` | list of compounds | one entry per slot, in slot order. Empty slots have no `tag` |
| `i[].id` / `i[].Count` / `i[].Damage` | short / byte / short | old numeric vanilla item id, ignore |
| `i[].tag.ExtraAttributes.id` | string | **SkyBlock item id**, e.g. `TERMINATOR` |
| `i[].tag.ExtraAttributes.upgrade_level` | int | **stars, newer items.** 0–10 = dungeon + master stars combined (seen values up to 10) |
| `i[].tag.ExtraAttributes.dungeon_item_level` | int | **stars, older items** (seen on an old `STARRED_BONZO_MASK`, value 5). Never seen together with `upgrade_level` |
| `i[].tag.ExtraAttributes.dungeon_item` | byte | not needed |
| `i[].tag.ExtraAttributes.rarity_upgrades` | int | 1 = recombobulated. Not needed: the lore already shows the upgraded rarity |
| `i[].tag.display.Lore` | list of strings | item text with `§` color codes. **Last line = rarity** (see below) |

Rule: stars = `upgrade_level` if present, else `dungeon_item_level`, else 0.

`inv_armor` slot order: `#0` boots, `#1` leggings, `#2` chestplate, `#3` helmet.

Last lore line of accessories (416 items in 5 real bags, every one fits one of these):
| Raw line | Meaning |
|---|---|
| `§9§lRARE ACCESSORY` | normal |
| `§6§lLEGENDARY DUNGEON ACCESSORY` | dungeon accessory: `DUNGEON` comes between rarity and type |
| `§d§l§ka§r §d§lMYTHIC ACCESSORY §d§l§ka` | recombobulated: scrambled (`§k`) letter on each side. Matched `rarity_upgrades` on all 416 items |
| `...SPECIAL HATCESSORY`, `...VERY SPECIAL HATCESSORY` | `PARTY_HAT_CRAB_ANIMATED`: `HATCESSORY` instead of `ACCESSORY`, and a two-word rarity |

Rarities seen: `COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY`, `MYTHIC`, `SPECIAL`, `VERY SPECIAL`.

### Item ids
Checked against our saved profiles (seen in data) and the public `GET /v2/resources/skyblock/items` list (`items[].id` / `items[].name`).
| Id | Name | Seen in a profile |
|---|---|---|
| `TERMINATOR` | Terminator | yes |
| `HYPERION` | Hyperion | yes |
| `SCYLLA` | Scylla | items list only |
| `ASTRAEA` | Astraea | items list only |
| `VALKYRIE` | Valkyrie | items list only |
| `DARK_CLAYMORE` | Dark Claymore | items list only |
| `POWER_WITHER_<PIECE>` | Necron's … | yes |
| `SPEED_WITHER_<PIECE>` | Maxor's … | yes |
| `WISE_WITHER_<PIECE>` | Storm's … | items list only |
| `TANK_WITHER_<PIECE>` | Goldor's … | items list only |
| `STARRED_<X>` | same name as `<X>` | e.g. `STARRED_SPIRIT_MASK` = "Spirit Mask" |

`<PIECE>` = `HELMET`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`. Helmet slot can also hold non-set items like `GOLD_NECRON_HEAD`, `STARRED_SPIRIT_MASK`, `COW_HEAD`.

### Accessories / MP
| Path | Type | Notes |
|---|---|---|
| `M.accessory_bag_storage.highest_magical_power` | number | highest MP ever reached, **not** current MP |
| `M.accessory_bag_storage.selected_power` | string | |
| `M.accessory_bag_storage.tuning.slot_<n>` | object | |
