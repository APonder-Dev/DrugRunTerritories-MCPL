# Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `DrugRunTerritories cannot start: config.yml has errors` | Each problem is listed below the line. Fix them and restart. Nothing was enabled. |
| `FactionsUUID is required and is not enabled` | Install FactionsUUID 4.6+ (`dev.kitteh.factions`). Older 1.6.9.5-U0.x builds use a different API and are not supported. |
| `Territories grant drug bonuses but DrugLab is not enabled` | Install DrugLab, remove the drug bonuses, or set `integrations.druglab.required-for-drug-bonuses: false`. |
| `could not load database migration: …` | SQLite: the plugin folder isn't writable. MySQL/MariaDB: wrong host or credentials, or the server is unreachable (timeout `storage.connection-timeout-ms`). |
| `Database schema version N is newer than this plugin supports` | The database was used by a newer DrugRunTerritories. Update the plugin; downgrading is refused to protect data. |
| Nobody can capture | Territory disabled (`/drugrun territories` doesn't list it), schedule closed, fewer than `minimum-faction-players`, players AFK or in creative, players lack `drugrun.territories.capture`, or the faction is at its territory limit. `/drugrun admin debug` shows live state. |
| Capture never progresses | Another faction's player is in the zone (CONTESTED), or FactionsUUID is unavailable (ticks are skipped; check the console with `general.debug: true`). |
| Can't build in an arena | Intended. Grant `drugrun.territories.bypass.arena` to builders. |
| Can't claim land near a territory | Intended: claims that touch a territory region are refused. |
| Dealer bonus missing | Seller not in the controlling faction, below `dealer.minimum-rank`, wrong world, the bonus would exceed the buy-back price, or the sale was a BUY. Turn on `general.debug` to log each bonus. |
| Income not paid | No Vault economy (`Vault off` in the startup line), no leader, ownership changed, or the cap was reached. `/drugrun admin economy audit failed` shows why. |
| Payouts marked UNKNOWN | The server stopped while a payout was in flight. They are never retried. Check the player's balance and pay manually if needed. |
| A DrugLab plant stopped growing | Its next stage would enter another faction's claim or an arena. Growth resumes when that land is unclaimed or the claim owner matches the plant's base. |
| Reload refused | The listed problems must be fixed first. The old configuration stays active. `storage.*` changes always need a restart. |
| `[missing message: key]` in chat | `messages.yml` lacks the key. It is added on the next start or reload, unless you deleted it on purpose. Set a message to `''` to silence it. |
