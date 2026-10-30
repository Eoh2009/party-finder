"""
Rebuilds src/client/resources/party-finder/accessory_families.json from NEU's data.
Only needed when new accessories come out. The mod itself never downloads anything.

  1. Download constants/misc.json from github.com/NotEnoughUpdates/NotEnoughUpdates-REPO
  2. python3 scripts/build_accessory_families.py <path to misc.json> <NEU commit id>

Output: a list of families. Each family is a list of tiers, lowest first, and each tier is a
list of ids that tie with each other. NEU's own format can't be used directly, see below.
"""
import json
import sys

OUTPUT = "src/client/resources/party-finder/accessory_families.json"

# NEU gives every abicase model its own id, but real bags only ever hold "ABICASE" (the model is a
# separate NBT field). So there's nothing to group: the mod's "same id counts once" rule covers it.
ABICASE_PREFIX = "ABICASE_"

# Same problem for hats: NEU has one id per color. These go in NEU's hat family too.
# PARTY_HAT_CRAB, PARTY_HAT_CRAB_ANIMATED and PARTY_HAT_SLOTH are verified (Hypixel's item list).
# The other three aren't: no real example yet. An id that doesn't exist can never match, so it's safe.
HAT_FAMILY_MEMBER = "PARTY_HAT_CRAB_RED"
REAL_HAT_IDS = ["PARTY_HAT_CRAB", "PARTY_HAT_CRAB_ANIMATED", "PARTY_HAT_SLOTH",
                "BALLOON_HAT_2024", "BALLOON_HAT_2025", "CAKE_HAT_2026"]


def tiers_by_id(upgrades):
    """Tier of an id = how many steps of real upgrades sit below it. A and B tie if each lists the other."""
    ids = set(upgrades) | {b for v in upgrades.values() for b in v}

    def is_upgrade(lower, higher):
        return higher in upgrades.get(lower, []) and lower not in upgrades.get(higher, [])

    tiers = {i: 0 for i in ids}
    # Each pass pushes an id above everything it upgrades from. It stops once nothing changes.
    changed = True
    while changed:
        changed = False
        for lower in ids:
            for higher in upgrades.get(lower, []):
                if is_upgrade(lower, higher) and tiers[higher] <= tiers[lower]:
                    tiers[higher] = tiers[lower] + 1
                    changed = True
    return tiers


def group_families(upgrades):
    """Ids linked by any upgrade, in either direction, are one family."""
    family = {}

    def find(i):
        family.setdefault(i, i)
        while family[i] != i:
            i = family[i]
        return i

    for lower, highers in upgrades.items():
        for higher in highers:
            family[find(lower)] = find(higher)

    groups = {}
    for i in list(family):
        groups.setdefault(find(i), set()).add(i)
    return groups.values()


def main():
    misc_path, commit = sys.argv[1], sys.argv[2]
    with open(misc_path, encoding="utf-8") as f:
        raw = json.load(f)["talisman_upgrades"]

    upgrades = {
        lower: [h for h in highers if not h.startswith(ABICASE_PREFIX)]
        for lower, highers in raw.items() if not lower.startswith(ABICASE_PREFIX)
    }
    tiers = tiers_by_id(upgrades)

    families = []
    for group in group_families(upgrades):
        by_tier = {}
        for i in group:
            by_tier.setdefault(tiers[i], []).append(i)
        family = [sorted(by_tier[t]) for t in sorted(by_tier)]
        if HAT_FAMILY_MEMBER in group:
            family[0] = sorted(family[0] + REAL_HAT_IDS)
        families.append(family)
    families.sort(key=lambda fam: fam[0][0])

    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
        f.write('{\n  "_source": ' + json.dumps(
            "NotEnoughUpdates-REPO constants/misc.json talisman_upgrades, commit " + commit
            + " (MIT license, see NEU-REPO-LICENSE.txt). Built by scripts/build_accessory_families.py"))
        f.write(',\n  "families": [\n')
        f.write(",\n".join("    " + json.dumps(fam, separators=(",", ":")) for fam in families))
        f.write("\n  ]\n}\n")
    print(len(families), "families,", sum(len(t) for fam in families for t in fam), "ids")


if __name__ == "__main__":
    main()
