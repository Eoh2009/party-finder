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
| `M.player_stats.kills.watcher*` | number | blood mob kills, many keys (e.g. `watcher_summon_undead`, `watcher_bonzo`) |
| `M.player_stats.kills.master_watcher*` | number | same, Master Mode |

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
| `M.inventory.bag_contents.talisman_bag` | accessory bag (for MP) |
| `M.inventory` (whole object) | missing when inventory API is off (to confirm) |

### Accessories / MP
| Path | Type | Notes |
|---|---|---|
| `M.accessory_bag_storage.highest_magical_power` | number | highest MP ever reached, **not** current MP |
| `M.accessory_bag_storage.selected_power` | string | |
| `M.accessory_bag_storage.tuning.slot_<n>` | object | |
