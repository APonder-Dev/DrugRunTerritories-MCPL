# Raid mechanics

## Principle

FactionsUUID decides raids: DTR, raidability, shields, grace, claim protection and TNT rules in claims. WorldGuard decides its regions. DrugRunTerritories **only ever removes blocks from an explosion, never adds any**, so it can't open a hole in either plugin's protection. Explosions are never cancelled as a whole, so TNT cannons keep working wherever FactionsUUID allows them.

## What DrugRunTerritories adds

Explosion block lists are filtered at LOW priority, before DrugLab (HIGH) and before logging plugins:

| Block | Land state | Result |
|---|---|---|
| Inside an ARENA territory | any | protected |
| Ordinary block | any | left to FactionsUUID / WorldGuard |
| DrugLab plant, soil or station | VULNERABLE (raidable) | left to DrugLab (`explosions-break-plants`) |
| DrugLab plant, soil or station | PROTECTED, SHIELDED, GRACE | protected (`protect-druglab-in-protected-claims`) |
| DrugLab plant, soil or station | SERVER_PROTECTED | protected |
| DrugLab plant, soil or station | WILDERNESS | `wilderness-druglab-explosions`: ALLOW or PREVENT |
| DrugLab block, raid state unreadable | — | protected (`fail-closed`) |

- A multi-block plant uses the land of its **base** block, so one plant can never be half-protected.
- An explosion crossing a claim border is evaluated per block owner. Each chunk owner is looked up once per explosion.
- A DTR-0 faction under a shield or in grace is not raidable.

Drops from allowed plant destruction follow `raiding.plant-explosion-drops`. Drops are only removed or reduced, never added, so explosions can't duplicate items.

## Indirect destruction of arenas

Inside ARENA territories these are blocked (players with `drugrun.territories.bypass.arena` excepted):

| Source | Event |
|---|---|
| Players breaking, placing, emptying or filling buckets | `BlockBreakEvent`, `BlockPlaceEvent`, `PlayerBucket*Event` |
| Fluids flowing in from outside | `BlockFromToEvent` |
| Pistons pushing or pulling into, out of or within the arena | `BlockPistonExtendEvent`, `BlockPistonRetractEvent` |
| Fire burning, spreading in, or ignition | `BlockBurnEvent`, `BlockSpreadEvent`, `BlockIgniteEvent` |
| Falling blocks, endermen, ravagers, withers, other entities | `EntityChangeBlockEvent` |
| Dispensers outside facing in | `BlockDispenseEvent` |
| Trees and structures growing in from outside | `StructureGrowEvent` (blocks inside are removed) |
| Item frames and paintings | `HangingBreakEvent` |

Every handler starts with a chunk-index lookup that returns immediately away from territories, so the cost on busy servers is a single hash lookup.

Other plugins that change blocks directly through the API without firing events can't be intercepted by any plugin. This is a Bukkit limitation.

## Raid victories

A **validated raid victory** is an enemy kill that makes the victim's faction raidable. DrugRunTerritories checks raidability one tick after the death, after FactionsUUID has applied DTR loss. It counts once per attacker/defender pair per `raid-victory-cooldown-hours` (default 6), so repeatedly dropping a faction's raidability can't be farmed. It grants `raid-victory` XP and reputation and can broadcast `raid.faction-raidable`.

## DrugLab crops in claims

See [DRUGLAB-INTEGRATION.md](DRUGLAB-INTEGRATION.md): footprint checks at planting, frozen growth instead of crossing claims, and access checks for harvest, fertilizer and stations.
