# Territories

Territories are server-owned districts, separate from faction claims. FactionsUUID claims that would touch a territory region are refused, so the two ownership systems never overlap.

## Setting up a district

The bundled `territories.yml` defines The Docks, Industrial District, Greenhouse District and Downtown with placeholder coordinates and `enabled: false`. For each one, stand in the district and run:

```
/drugrun admin territory setregion docks pos1      (one corner)
/drugrun admin territory setregion docks pos2      (opposite corner)
/drugrun admin territory setcapture docks 8         (capture point here, radius 8)
/drugrun admin territory enable docks
/drugrun territory info docks
```

New districts: `/drugrun admin territory create harbour DOCKS`, then the same steps.

## Definition keys (`territories.yml`)

| Key | Meaning |
|---|---|
| `display-name` | MiniMessage name |
| `type` | DOCKS, INDUSTRIAL, GREENHOUSE, DOWNTOWN or CUSTOM. Descriptive only; effects come from `bonuses` and `income` |
| `enabled` | Disabled territories are not capturable and not protected |
| `world`, `region.min`, `region.max` | Inclusive block box. Enabled regions may not overlap |
| `capture.center`, `capture.radius` | Capture cylinder: horizontal distance ≤ radius, vertical distance ≤ radius. Must lie inside the region |
| `capture.duration-seconds`, `capture.minimum-faction-players`, `capture.ownership-duration-hours`, `capture.cooldown-minutes` | Optional per-territory overrides of the global defaults |
| `allowed-factions` | Faction ids or tags allowed to capture; empty = all |
| `schedule` | Daily windows, e.g. `["18:00-22:00", "22:00-02:00"]`, server time; empty = always |
| `protection` | ARENA (protected against griefing) or NONE |
| `pvp` | DEFAULT (leave to FactionsUUID/WorldGuard) or DENY |
| `bonuses` | `DEALER_SELL`, `PROCESSING_SPEED`, `CROP_GROWTH` → multiplier 1.0-3.0 |
| `income` | `amount`, `interval-minutes`, `max-payouts-per-ownership` (0 = unlimited) |
| `rewards` | One-off `xp`, `reputation`, `money` for the capturing faction |

## Capture rules

A player counts towards a capture when they are online, alive, not a spectator (nor creative, by default), not vanished, not AFK, hold `drugrun.territories.capture`, belong to a faction, and stand in the capture cylinder. Only members of the capturing faction count. Allies add nothing.

Capture zones are checked once per `tick-interval-ticks` (default 1 s) using `World#getNearbyPlayers` around each zone. There is no scan of every online player.

## State machine

```
              faction alone with >= min players
  AVAILABLE ------------------------------------> CAPTURING <------------+
      ^                                            |   ^   |              |
      |   capture fails, no previous owner         |   |   | other faction enters
      +--------------------------------------------+   |   v              |
      |                                               CONTESTED ----------+
      |                                                 |    others leave
      |              progress complete                  |
      |        +--------------------------------------> CONTROLLED ---------+
      |        |   (from CAPTURING)       challenge     |    ^              |
      |        |                          after cooldown|    | challenge fails (owner keeps it)
      |        |                                        +----+              |
      |                                                         ownership ends
   COOLDOWN <------------------------------- EXPIRED <----------------------+
      cooldown ends -> AVAILABLE              (EXPIRED -> AVAILABLE directly when cooldown is 0)
```

Legal gameplay transitions (enforced in `CaptureState`):

| From | To | When |
|---|---|---|
| AVAILABLE | CAPTURING | exactly one faction in the zone with at least the minimum, allowed, under its territory limit, schedule open |
| CAPTURING | CONTESTED | another faction's eligible player enters while the capturer is present |
| CAPTURING | CONTROLLED | progress reaches the duration, or a challenge fails |
| CAPTURING | AVAILABLE | the capture fails with no previous owner |
| CONTESTED | CAPTURING | the zone holds only the capturer again |
| CONTESTED | CONTROLLED / AVAILABLE | the capture fails (with / without a previous owner) |
| CONTROLLED | CAPTURING | a non-allied challenger starts after the post-capture cooldown, with the owner absent |
| CONTROLLED | EXPIRED | ownership time runs out |
| EXPIRED | COOLDOWN / AVAILABLE | immediately |
| COOLDOWN | AVAILABLE | the cooldown ends |

Administrative reset, force-capture and disband revocation may move any state to AVAILABLE, COOLDOWN or CONTROLLED.

Progress:
- While capturing, progress grows by the elapsed time, capped at 2 ticks' worth per evaluation so server lag can't fast-forward a capture.
- Contested: frozen (PAUSE) or drained (DECAY).
- Capturer below the minimum: drains by `decay-per-second` until it reaches 0 (then the capture fails), or fails at once when the zone is empty and `decay-when-abandoned` is false.
- A challenge is **defended** when the owner's players contested it before it failed. The owner then gets defence XP.

Only one faction can be capturing a territory at a time, and completion happens within one evaluation on the main thread, so two factions can never both complete a capture.

## Restarts

State is saved immediately on every state or owner change, and progress every 15 s. On restart, territories resume where they were. Ownership that ended while the server was down expires on the first evaluation. Disbanded owners are released on the first evaluation.
