# DrugLab integration

DrugRunTerritories integrates with DrugLab-MCPL through DrugLab's **public API and events only**. It never edits DrugLab files or data. Four small, backward-compatible API additions were made to DrugLab for this integration (see "DrugLab API additions" below; DrugLab `CHANGELOG.md`, section Unreleased). DrugRunTerritories also works with DrugLab 1.0.0, without the features that need them. Everything lives in `dev.aponder.drugrun.territories.druglab`. That is the only package that references DrugLab classes, so DrugRunTerritories also runs without DrugLab installed.

## Verified DrugLab API used

| DrugLab API | Used for |
|---|---|
| `PlantPlaceEvent` (cancellable, player, plant type, base, rotation) | Full-footprint planting authorization |
| `PlantGrowEvent` (cancellable, plant, next stage) | Re-check new blocks before each stage |
| `PlantHarvestEvent` (cancellable, player or `null`, plant, mature, mutable drops, XP) | Harvest authorization, explosion drop modes, production stats |
| `FertilizerUseEvent` (cancellable) | Fertilizer authorization |
| `ShopTransactionEvent` (cancellable, `setPrice`) | Dealer sale bonus |
| `ModifierProvider`, `PlantDestroyEvent`, `ShopTransactionCompleteEvent`, `StationProcessCompleteEvent` (added, see below) | Growth/processing bonuses, explosion drops, sale and processing statistics |
| `Plant#blocksOf(GrowthStage)`, `Plant#soil()`, `PlantType#stages()` | Exact footprint, including rotation |
| `DrugLabAPI.getPlant/getStation/getPlantType/getMultiplier/isEnabled` | Block lookups, buy-back ceiling |
| `PlayerInteractEvent#useInteractedBlock() == DENY` (DrugLab's `StationListener` honours it) | Station access |

## What works today

- **Faction-aware planting:** every block the plant will occupy in any stage, plus its soil, must be buildable by the planter under FactionsUUID and WorldGuard, and outside territory arenas. Planting is refused before anything is placed, so a structure is never partly placed across a boundary.
- **Growth boundary protection:** before each stage change, the blocks the new stage *adds* must be in the same land as the plant's base, or in wilderness, and not in an arena. If a claim changed since planting, growth **freezes**: nothing is placed and nothing is destroyed. Growth resumes when the land allows it.
- **Harvest and fertilizer:** need FactionsUUID break or build access at the plant's base.
- **Stations:** opening a station needs FactionsUUID container access. The listener runs before DrugLab's and denies the interaction, so the input and output inventories are never shown to an unauthorized player.
- **Explosions:** DrugLab plant and station blocks in claims that are not raidable are removed from explosion lists *before* DrugLab sees them, so they can't be blown up or TNT-harvested. When DrugLab's `explosions-break-plants` is on and a plant is legitimately destroyed, drops follow `raiding.plant-explosion-drops` (NORMAL, REDUCED or NONE), through `PlantDestroyEvent` (or, on DrugLab 1.0.0, its null-player `PlantHarvestEvent`). Drops are only edited, never added, so nothing is duplicated.
- **Dealer bonus (Docks):** the bonus multiplies DrugLab's *finished* sale price, which already includes quality, demand, the daily request and boosters. DrugLab still makes the single Vault deposit. The bonus is capped per transaction and by the stacking hard cap, and never exceeds the dealer's buy-back price.
- **Production statistics:** mature harvests by faction members count, read at MONITOR on non-cancelled events.

## DrugLab API additions

These were added to DrugLab (uncommitted in the DrugLab working tree, under `Unreleased` in its changelog). DrugRunTerritories detects them at startup (`DrugLabIntegration.hasExtendedApi()`). With DrugLab 1.0.0 the features below stay off and a warning is logged; everything else keeps working.

| Addition (DrugLab) | What DrugRunTerritories does with it |
|---|---|
| `api.modifier.ModifierProvider` service; `PlantManager.speedMultiplier` and `StationManager.start` consult it, capped by DrugLab's `modifiers.max-growth-multiplier` / `max-processing-multiplier` (1.5) | `TerritoryModifierProvider` returns the owning faction's CROP_GROWTH / PROCESSING_SPEED territory multiplier, checked live on every call |
| `api.event.PlantDestroyEvent` (no player; cause EXPLOSION or PLUGIN). Explosions no longer fire `PlantHarvestEvent` with a `null` player | Explosion drops follow `raiding.plant-explosion-drops` |
| `api.event.ShopTransactionCompleteEvent`, fired after the economy confirmed the payment | Faction sale statistics and `sale-milestone` XP. Unpaid or cancelled sales never count |
| `api.event.StationProcessCompleteEvent`, fired when a finished job is collected | Faction processing statistics and `processing-milestone` XP |

Safety properties of the growth and processing hook (enforced in DrugLab):
- Multipliers only speed things up (values below 1.0, NaN or infinite are ignored) and are capped.
- Growth conditions (light, water, biome, soil, weather, world) are checked before growth progresses, so they are never bypassed. Offline catch-up stays limited by `plants.max-offline-growth`.
- Processing time is fixed when a job starts: recipes and ingredient checks are unchanged, outputs are not duplicated, and restarting or changing ownership mid-job has no effect on a running job.
- On Paper, growth is asked on the main thread. DrugRunTerritories returns 1.0 off the main thread, so it never reads faction state from the wrong thread.

DrugLab regression tests: `ApiHookTest` (combining and capping, duration scaling, neutral defaults, event contracts). Full event dispatch (fired once, cancel keeps the plant) needs a running server: run DrugLab's `/druglab selftest` on a test server.

To release: bump DrugLab's version (for example 1.1.0), then point `druglab.version` in this project's `pom.xml` at it.

## Configuration

```yaml
integrations:
  druglab:
    enabled: true
    required-for-drug-bonuses: true   # refuse to start without DrugLab when territories grant drug bonuses
druglab:
  plants: { check-full-footprint: true, freeze-on-unauthorized-growth: true,
            require-access-to-harvest: true, require-access-to-fertilize: true }
  stations: { require-container-access: true }
```
