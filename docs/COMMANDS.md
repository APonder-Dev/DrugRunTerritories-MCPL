# Commands and placeholders

The only root command is `/drugrun`, with no aliases, so nothing clashes with FactionsUUID's `/f`. Every sub-command checks its permission ([PERMISSIONS.md](PERMISSIONS.md)), validates input, and tab-completes. Administrative actions are written to the audit log (database table `drt_audit_log` and the server log).

## Player commands

| Command | Description |
|---|---|
| `/drugrun help` | Lists commands (admin commands too, for admins) |
| `/drugrun territories` | All enabled territories with status and owner |
| `/drugrun territory info [id]` | Details of a territory; without an id, the one you stand in |
| `/drugrun territory map` | Distance and compass direction to each territory |
| `/drugrun territory leaderboard [xp\|reputation\|captures\|income]` | Top 10 factions |
| `/drugrun faction stats` | Your faction's territories and statistics |
| `/drugrun faction progression` | Level, XP, reputation, territory limit |
| `/drugrun faction upgrades` | DrugRun level unlocks (FactionsUUID's own upgrades stay under `/f upgrades`) |

## Administrator commands

| Command | Description |
|---|---|
| `/drugrun admin reload` | Reloads config, messages and territories. Refused, with the reasons listed, if anything is invalid |
| `/drugrun admin debug` | Integration status, storage, and every territory's live state |
| `/drugrun admin territory create <id> [type]` | New territory centred on you: a 49x49 region for the full world height, capture point at your feet, radius 8 |
| `/drugrun admin territory setregion <id> pos1\|pos2` | Moves one region corner to your position |
| `/drugrun admin territory setcapture <id> [radius]` | Moves the capture point to your position (radius 1-64) |
| `/drugrun admin territory enable\|disable <id>` | Turns a territory on or off |
| `/drugrun admin territory reset <id>` | Clears owner, capture and cooldown |
| `/drugrun admin territory forcecapture <id> <faction>` | Gives the territory to a faction now (a full ownership period) |
| `/drugrun admin territory delete <id> confirm` | Deletes the definition and its stored state |
| `/drugrun admin faction resetstats <faction> confirm` | Clears a faction's progression |
| `/drugrun admin economy audit [all\|paid\|failed\|unknown]` | Recent payouts; defaults to UNKNOWN (interrupted) ones |

Edits are validated (no overlapping regions, capture point inside the region, at most 16,384 chunks per region) before `territories.yml` is written atomically.

## PlaceholderAPI (`drugrun` expansion)

Only these placeholders exist:

| Placeholder | Value |
|---|---|
| `%drugrun_territory_name%` | Territory you stand in (plain text), `-` when none |
| `%drugrun_territory_owner%` | Its owner, `-` when none |
| `%drugrun_territory_status%` | AVAILABLE, CAPTURING, CONTESTED, CONTROLLED, COOLDOWN |
| `%drugrun_territory_progress%` | Capture progress 0-100 |
| `%drugrun_territory_<id>_name%` / `_owner%` / `_status%` / `_progress%` | The same for a named territory, e.g. `%drugrun_territory_docks_owner%` |
| `%drugrun_faction_xp%` | Your faction's XP |
| `%drugrun_faction_level%` | Your faction's level |
| `%drugrun_faction_reputation%` | Your faction's reputation |
| `%drugrun_faction_territories%` | Territories your faction controls |
| `%drugrun_faction_income%` | Territory income your faction has received |
| `%drugrun_faction_captures%` | Captures by your faction |
| `%drugrun_faction_sales%` | Items your faction's members sold to DrugLab dealers |
| `%drugrun_faction_processed%` | DrugLab processing jobs your faction collected |

A DeluxeMenus example using these is in `examples/deluxemenus-territories.yml`.
