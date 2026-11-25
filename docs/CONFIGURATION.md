# Configuration

Three files live in `plugins/DrugRunTerritories/`:

| File | Purpose | Merged with new defaults on update |
|---|---|---|
| `config.yml` | All gameplay and integration settings | yes, with a backup in `backups/` first |
| `messages.yml` | Every player-facing text (MiniMessage) | yes |
| `territories.yml` | Territory definitions | **no** (deleted territories stay deleted) |

Every key is a DrugRunTerritories key. None of them configure FactionsUUID, DrugLab or DrugRunCore.

## Validation

All values are range-checked on startup and on `/drugrun admin reload`. Problems are listed together. On startup the plugin disables itself; on reload the previous configuration stays active. These combinations are rejected:

- `prevent-same-faction-kills: false` (would allow Loot Key farming)
- `respect-factionsuuid: false` (FactionsUUID is authoritative)
- `contested-behavior: DECAY` with `decay-per-second: 0`
- `absolute-max-territories` below `maximum-per-faction`
- level XP that does not start at 0 or does not strictly increase
- a `config-version` newer than the plugin understands
- unsafe `storage.table-prefix` or `sqlite-file` values

`storage.*` changes need a restart. Everything else applies on reload.

## Key reference

### `territories.capture`

| Key | Default | Meaning |
|---|---|---|
| `duration-seconds` | 600 | Seconds of uncontested holding needed (10-86400) |
| `minimum-faction-players` | 2 | Eligible members needed in the zone |
| `contested-behavior` | PAUSE | PAUSE freezes progress while contested; DECAY drains it |
| `decay-per-second` | 1.0 | Progress lost per second while decaying |
| `decay-when-abandoned` | true | Drain progress when the capturer drops below the minimum; false fails the capture as soon as the zone is empty |
| `ownership-duration-hours` | 24 | How long a captured territory stays owned |
| `cooldown-minutes` | 60 | No captures or challenges for this long after a capture or expiry |
| `maximum-per-faction` | 2 | Base territory limit (levels can add slots) |
| `afk-timeout-seconds` | 120 | Players who haven't moved or turned for this long don't count (0 disables) |
| `exclude-creative` | true | Creative players don't count; spectators never do |
| `allow-challenges` | true | Controlled territories can be challenged after the cooldown |

### `territories.bonuses`

| Key | Default | Meaning |
|---|---|---|
| `stacking` | CAPPED_MULTIPLICATIVE | HIGHEST, ADDITIVE or CAPPED_MULTIPLICATIVE |
| `hard-cap` | 1.25 | No combination exceeds this multiplier |
| `dealer.allowed-worlds` | [] | Worlds where the dealer bonus applies (empty = all) |
| `dealer.minimum-rank` | RECRUIT | Lowest faction rank that gets the bonus |
| `dealer.max-bonus-per-transaction` | 5000 | Largest amount the bonus adds to one sale (0 = no cap) |

### `territories.income`

| Key | Default | Meaning |
|---|---|---|
| `pay-missed-periods` | false | Pay periods that ended while the server was offline |
| `recipient` | LEADER | LEADER, or ONLINE_MEMBERS to split each payout |

### `territories.protection`

`prevent-arena-griefing` (true), `respect-factionsuuid` (must stay true), `respect-worldguard` (true).

### `raiding`

| Key | Default | Meaning |
|---|---|---|
| `protect-druglab-in-protected-claims` | true | DrugLab blocks in claims that aren't raidable survive explosions |
| `wilderness-druglab-explosions` | ALLOW | ALLOW lets DrugLab decide; PREVENT protects wilderness farms |
| `fail-closed` | true | Protect DrugLab blocks when the raid state can't be read |
| `plant-explosion-drops` | NORMAL | NORMAL, REDUCED or NONE for plants destroyed by allowed explosions |
| `reduced-drop-fraction` | 0.5 | Share kept by REDUCED |

### `druglab`, `progression`, `loot-keys`, `announcements`

See [DRUGLAB-INTEGRATION.md](DRUGLAB-INTEGRATION.md), [PROGRESSION.md](PROGRESSION.md), [DRUGRUNCORE-INTEGRATION.md](DRUGRUNCORE-INTEGRATION.md) and the comments in `config.yml`. Sounds are namespaced keys as used by `/playsound` (for example `entity.player.levelup`). Particles are Bukkit particle names.

### `storage`, `performance`

| Key | Default | Meaning |
|---|---|---|
| `storage.type` | SQLITE | SQLITE, MYSQL or MARIADB |
| `storage.table-prefix` | drt_ | Letters, digits and _ only |
| `storage.pool-size` | 4 | MySQL/MariaDB connections (SQLite always uses 1) |
| `storage.query-timeout-seconds` | 10 | Per-statement timeout |
| `performance.async-threads` | 2 | Database worker threads (SQLite always uses 1) |
| `performance.async-queue-size` | 2000 | Pending database tasks before new ones are rejected and logged |
| `performance.progress-save-interval-seconds` | 15 | How often capture progress is written |

## Example: three-district server with stronger Downtown

```yaml
territories:
  capture:
    duration-seconds: 900
    minimum-faction-players: 3
    maximum-per-faction: 1
  bonuses:
    stacking: HIGHEST
    hard-cap: 1.15
  income:
    recipient: ONLINE_MEMBERS
```

with Downtown in `territories.yml`:

```yaml
  downtown:
    income: { amount: 500.0, interval-minutes: 30, max-payouts-per-ownership: 48 }
```
