# Faction progression

Progression rewards confirmed outcomes only: completed captures, successful defences, validated raid victories, committed harvests, and time in control. Starts, failures and cancelled events grant nothing.

## XP and reputation (defaults)

| Source | XP | Reputation |
|---|---|---|
| Territory capture | 500 (+ territory `rewards.xp`) | 25 (+ territory `rewards.reputation`) |
| Territory defence (challenge failed while defenders contested it) | 250 | 10 |
| Validated raid victory ([RAID-MECHANICS.md](RAID-MECHANICS.md)) | 300 | 15 |
| Every 50 mature harvests by members | 100 | 0 |
| Each minute holding a territory | 1 | 0 |
| Every 500 items sold to DrugLab dealers (paid sales only) | 100 | 0 |
| Every 25 DrugLab processing jobs collected | 50 | 0 |

These are proposed defaults defined by DrugRunTerritories, not values from another plugin. Change them under `progression.xp` and `progression.reputation`. With `progression.enabled: false`, statistics are still counted but no XP or reputation is granted.

Sale and processing XP need a DrugLab build with `ShopTransactionCompleteEvent` and `StationProcessCompleteEvent` ([DRUGLAB-INTEGRATION.md](DRUGLAB-INTEGRATION.md)). Sales count only after DrugLab confirmed the payment, so cancelled or failed transactions never grant anything. Keys: `progression.xp.sale-milestone-items`, `sale-milestone-xp`, `processing-milestone-jobs`, `processing-milestone-xp` (0 disables).

## Levels

```yaml
progression:
  levels:
    - xp: 0
    - xp: 1000
    - xp: 2500
      extra-territory-slots: 1
    - xp: 5000
    - xp: 10000
      extra-territory-slots: 1
  absolute-max-territories: 3
```

A faction's territory limit is `maximum-per-faction` plus the slots of every level reached, never above `absolute-max-territories`. The only unlock in 0.1.0 is extra territory slots. None of them touch raid protection, so progression can't buy a way around FactionsUUID's rules. FactionsUUID's own upgrades (`/f upgrades`) are unchanged.

## Statistics

Per faction: captures, defences, raid victories, seconds in control, mature harvests, items sold and their value, processing jobs, income received, XP, reputation. They are shown by `/drugrun faction stats`, `/drugrun faction progression`, the leaderboard and the placeholders.

Storage: `drt_faction_progress`, written as increments (`xp = xp + ?`) so concurrent changes add up. A disbanded faction's statistics are kept. Clear them with `/drugrun admin faction resetstats <faction> confirm`.
