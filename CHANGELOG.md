# Changelog

All notable changes to DrugRunTerritories. Versions follow [Semantic Versioning](https://semver.org).

## [0.1.0-SNAPSHOT] - 2026-11-24

First development build.

### Fixed (QA pass, 2026-11-27)
- The last income period of an ownership was usually lost: ownership expired (or changed hands) up to 30 seconds before the
  income check saw that period. Periods that finished before an ownership ended are now settled when it ends.
- `recipient: ONLINE_MEMBERS` lost the payout when no member was online; it now pays the leader instead.
- Players hovering at a zone edge could make start/fail and contested messages repeat every second. They now repeat at most
  once per `announcements.repeat-cooldown-seconds` (default 30) per territory. The contested sound plays only inside that territory.
- After a restart, a capture in progress drained or failed before its players could reconnect. It is now held for
  `territories.capture.restart-grace-seconds` (default 120). Only captures that were running at shutdown get the grace.
- Progression wrote one database row per harvest, sale and job. Writes are now merged per faction and flushed every
  5 seconds and on shutdown, with failed writes retried.
- A capture duration equal to or longer than the ownership duration is rejected at load.

### Added (DrugLab hooks)
- CROP_GROWTH and PROCESSING_SPEED territory bonuses now apply through DrugLab's new `ModifierProvider` service.
- Explosion drop modes use DrugLab's new `PlantDestroyEvent`.
- Faction sale statistics and sale-milestone XP from `ShopTransactionCompleteEvent` (paid sales only); processing statistics and
  milestone XP from `StationProcessCompleteEvent`. New placeholders `%drugrun_faction_sales%`, `%drugrun_faction_processed%`.
- Database migration 2 adds `items_sold`, `sales_value`, `jobs_processed`.
- These features switch off automatically, with a startup warning, on DrugLab 1.0.0.

### Added
- Territory capture system: four districts (Docks, Industrial, Greenhouse, Downtown), a six-state capture machine with documented legal transitions, contested PAUSE/DECAY, abandonment decay, challenges and defences, ownership expiry, cooldowns, per-faction limits, capture schedules, AFK/spectator/creative/vanish exclusion, and lag-capped progress.
- FactionsUUID 4.6.0 adapter (`dev.kitteh.factions`): membership, ranks, land ownership, relations, raid states (grace, shield, peaceful, raidable), build/break/container access, claim blocking inside territories, and disband handling with a missed-disband safety net.
- Bonus framework with HIGHEST, ADDITIVE and CAPPED_MULTIPLICATIVE stacking and a hard cap; DrugLab dealer sale bonus with a per-transaction cap and a buy-back ceiling.
- Downtown income and capture money rewards on an idempotent, claim-first payout ledger with restart recovery (PENDING → UNKNOWN, never retried) and full auditing.
- Raid coordination: explosion filtering that never adds destruction, arena protection against players, fluids, pistons, fire, entities, dispensers, structure growth and hanging entities, and per-territory PvP denial.
- DrugLab integration: full-footprint planting checks, growth freezing at claim borders, harvest/fertilizer/station access, explosion drop modes, and production statistics.
- Loot Key faction policy (`LootKeyFactionPolicy`) for DrugRunCore, registered as a Bukkit service; fail-closed `UNAVAILABLE` outcome.
- Faction progression: XP, reputation, levels unlocking territory slots, capture/defence/raid-victory/harvest/control-time statistics, validated raid victories with a per-pair cooldown, and leaderboards.
- `/drugrun` command tree with permissions, validation, tab completion and audit logging.
- BossBars, ActionBars, MiniMessage announcements, sounds and particles; PlaceholderAPI expansion `drugrun`.
- Built-in stand-ins for DrugRunCore's Files and Databases APIs: atomic YAML files with default merging and backups; HikariCP SQLite/MySQL/MariaDB storage with a bounded executor and versioned migrations.
- Public `DrugRunTerritoriesApi` service.
- Maven build, GitHub Actions CI, and 136 automated tests.

### Known limitations
- DrugRunCore is not released; the plugin runs on its own config and storage stand-ins.
- Growth/processing bonuses and sale/processing statistics need the patched DrugLab (not yet released as a new DrugLab version).
- Not yet run on a live Paper 26.3 server (see `docs/TESTING.md`).
