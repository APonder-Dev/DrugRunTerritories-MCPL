# Permissions

| Node | Default | Grants |
|---|---|---|
| `drugrun.territories.*` | op | Everything below |
| `drugrun.territories.player` | everyone | All player commands and `capture` |
| `drugrun.territories.capture` | everyone | Counting towards territory captures. Remove it from staff or alt groups |
| `drugrun.territories.command.help` | everyone | `/drugrun help` |
| `drugrun.territories.command.territories` | everyone | `/drugrun territories` |
| `drugrun.territories.command.info` | everyone | `/drugrun territory info` |
| `drugrun.territories.command.map` | everyone | `/drugrun territory map` |
| `drugrun.territories.command.leaderboard` | everyone | `/drugrun territory leaderboard` |
| `drugrun.territories.command.faction.stats` | everyone | `/drugrun faction stats` |
| `drugrun.territories.command.faction.progression` | everyone | `/drugrun faction progression` |
| `drugrun.territories.command.faction.upgrades` | everyone | `/drugrun faction upgrades` |
| `drugrun.territories.admin` | op | All admin commands below |
| `drugrun.territories.admin.reload` | op | `/drugrun admin reload` |
| `drugrun.territories.admin.debug` | op | `/drugrun admin debug` |
| `drugrun.territories.admin.territory.create` | op | `create`, `enable`, `disable` |
| `drugrun.territories.admin.territory.delete` | op | `delete` |
| `drugrun.territories.admin.territory.setcapture` | op | `setcapture` |
| `drugrun.territories.admin.territory.setregion` | op | `setregion` |
| `drugrun.territories.admin.territory.reset` | op | `reset` |
| `drugrun.territories.admin.territory.forcecapture` | op | `forcecapture` |
| `drugrun.territories.admin.faction.resetstats` | op | `faction resetstats` |
| `drugrun.territories.admin.economy.audit` | op | `economy audit` |
| `drugrun.territories.bypass.arena` | **false** | Build, break, bucket and ignite inside protected arenas. Not granted to ops by default, so admins do not grief arenas by accident |

Notes:
- FactionsUUID's admin bypass (`/f bypass`) also counts for DrugLab plant and station checks, because those checks ask FactionsUUID.
- Permission checks run server-side for every command. Tab completion only hides what you can't use.
