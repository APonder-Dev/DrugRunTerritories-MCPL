# DrugRunTerritories

Faction territory warfare for the **DrugRun** Minecraft server: capturable districts, territory bonuses for the drug economy, raid coordination, faction progression, and the faction rules behind DrugRunCore's PvP Loot Keys.

- **Platform:** Paper 26.3 (paper-plugin), Java 25, Maven
- **Version:** 0.1.0-SNAPSHOT
- **Author:** Anthony Ponder / APonder.dev

## What it does

| Area | Summary |
|---|---|
| Territories | Server-owned districts (The Docks, Industrial District, Greenhouse District, Downtown) separate from faction claims. Factions capture them by holding a capture zone. |
| Capture | 10-minute captures with at least 2 members, contested and abandoned handling, 24-hour ownership, 60-minute cooldown, at most 2 territories per faction. Six-state machine: AVAILABLE, CAPTURING, CONTESTED, CONTROLLED, COOLDOWN, EXPIRED. |
| Bonuses | DrugLab dealer sale bonus (+10% at the Docks), faster processing (Industrial) and crop growth (Greenhouse) through DrugLab's `ModifierProvider` hook, and recurring Vault income (Downtown), all with capped stacking. |
| Raids | FactionsUUID stays authoritative. DrugRunTerritories never adds destruction. It protects territory arenas from every kind of griefing, and keeps DrugLab crops and stations safe in claims that are not raidable. |
| DrugLab | Plant footprints checked across all growth stages, growth frozen instead of crossing claims, harvest/fertilizer/station access tied to FactionsUUID permissions, and explosion drop modes. |
| Loot Keys | Registers `LootKeyFactionPolicy` for DrugRunCore. It covers same-faction, allied, enemy, safezone, warzone and wilderness rules. Key storage and redemption stay in DrugRunCore. |
| Progression | Faction XP, reputation, levels that unlock territory slots, capture/defence/raid-victory/production statistics, and leaderboards. |
| UI | BossBars, ActionBars, MiniMessage announcements, sounds, particles, and PlaceholderAPI placeholders. |

## Quick start

1. Install **FactionsUUID 4.6+** (required) and optionally DrugLab, Vault, PlaceholderAPI, WorldGuard.
2. Build the plugin (see [INSTALLATION.md](docs/INSTALLATION.md)) or use a CI artifact, and drop `DrugRunTerritories-0.1.0-SNAPSHOT.jar` in `plugins/`.
3. Start the server once. Edit `plugins/DrugRunTerritories/config.yml`.
4. The four bundled districts start **disabled** with placeholder coordinates. In-game, at each district:
   ```
   /drugrun admin territory setregion docks pos1
   /drugrun admin territory setregion docks pos2
   /drugrun admin territory setcapture docks 8
   /drugrun admin territory enable docks
   ```

## Project status

Built and tested: 136 automated tests pass (unit, SQLite integration, MockBukkit and Mockito). See [TESTING.md](docs/TESTING.md) for what was **not** run, notably a live Paper 26.3 server.

Known limitations:
- **DrugRunCore is not released yet.** Config and storage run on built-in stand-ins behind the same interfaces DrugRunCore will provide ([DRUGRUNCORE-INTEGRATION.md](docs/DRUGRUNCORE-INTEGRATION.md)).
- **Growth and processing bonuses, sale and processing statistics need the patched DrugLab** (its `Unreleased` changelog entry). With DrugLab 1.0.0 they stay off and the startup log says so. See [DRUGLAB-INTEGRATION.md](docs/DRUGLAB-INTEGRATION.md).

## Documentation

[Installation](docs/INSTALLATION.md) · [Configuration](docs/CONFIGURATION.md) · [Commands & placeholders](docs/COMMANDS.md) · [Permissions](docs/PERMISSIONS.md) · [Territories](docs/TERRITORIES.md) · [Raid mechanics](docs/RAID-MECHANICS.md) · [Economy](docs/ECONOMY.md) · [Progression](docs/PROGRESSION.md) · [FactionsUUID](docs/FACTIONS-INTEGRATION.md) · [DrugLab](docs/DRUGLAB-INTEGRATION.md) · [DrugRunCore](docs/DRUGRUNCORE-INTEGRATION.md) · [Testing](docs/TESTING.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Changelog](CHANGELOG.md)

## Source layout

```
src/main/java/dev/aponder/drugrun/territories/
  DrugRunTerritoriesPlugin.java   wiring, startup, reload
  api/          public API: DrugRunTerritoriesApi, LootKeyFactionPolicy, RaidState, KillDecision
  audit/        audit log
  bonus/        bonus types, stacking calculator
  command/      /drugrun
  config/       ConfigFiles seam, Settings, validation
  druglab/      DrugLab listeners (only package that touches DrugLab classes)
  economy/      Vault gateway, payout ledger, income service
  faction/      FactionProvider seam, FactionsUUID adapter, claim/disband listener
  hook/         PlaceholderAPI, WorldGuard
  lootkey/      Loot Key faction policy
  progression/  XP, reputation, levels, statistics
  raid/         explosion filter, arena protection, raid victories
  storage/      DataStore seam, HikariCP implementation, migrations
  territory/    definitions, registry, capture engine, service, editor
  ui/           messages, BossBars, announcements
```
