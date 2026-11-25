# DrugRunCore integration

## Current state

DrugRunCore has **not been released**. Only its specification exists. DrugRunTerritories therefore runs on two built-in stand-ins, each behind the interface DrugRunCore will fill:

| DrugRunCore API (planned) | Seam in DrugRunTerritories | Stand-in used today |
|---|---|---|
| Files API | `config.ConfigFiles` | `config.LocalConfigFiles`: default creation, default-key merging with backup, atomic saves, path-traversal guard |
| Databases API | `storage.DataStore` | `storage.HikariDataStore`: SQLite/MySQL/MariaDB, bounded executor, transactions, query timeouts, migrations |
| Loot Key faction-policy interfaces | `api.LootKeyFactionPolicy` (provided **by** this plugin) | registered with Bukkit's `ServicesManager` |

Gameplay code depends only on the seams. Switching to DrugRunCore means adding two adapter classes (`CoreConfigFiles implements ConfigFiles`, `CoreDataStore implements DataStore`) and choosing them in `DrugRunTerritoriesPlugin.start()` when DrugRunCore is enabled. No feature code changes.

`integrations.drugruncore.required: false` is the default. Setting it to `true` makes DrugRunTerritories refuse to start without DrugRunCore, which is useful once DrugRunCore ships. The startup log line says whether DrugRunCore is present.

## Loot Key faction policy

DrugRunCore owns the whole Loot Key lifecycle: generation, storage, redemption, remaining uses, expiry, consumption, physical disintegration, persistence. DrugRunTerritories implements none of that. A test (`ArchitectureTest`) fails the build if Loot Key storage or redemption code appears here. The five-stack allowance and disintegration are untouched.

DrugRunTerritories only answers one question: do faction rules allow this kill to earn a key?

```java
LootKeyFactionPolicy policy = Bukkit.getServicesManager().load(LootKeyFactionPolicy.class);
if (policy == null) {
    // DrugRunTerritories absent: apply DrugRunCore's own fail-closed rule for faction checks
}
KillDecision d = policy.evaluate(killer.getUniqueId(), victim.getUniqueId(), victim.getLocation());
if (!d.allowed()) {           // DENY or UNAVAILABLE
    return;                   // no key
}
```

Main thread only. `UNAVAILABLE` (FactionsUUID can't be read) must be treated as deny.

### Rules (first match wins)

| # | Situation | Default |
|---|---|---|
| 1 | Self-kill | deny |
| 2 | Faction data unavailable | UNAVAILABLE |
| 3 | Same faction | deny (cannot be turned off) |
| 4 | Ally, or truce with `treat-truce-as-ally` | deny |
| 5 | Inside a territory listed in `territory-overrides` | forced ALLOW or DENY (cannot re-enable 3 or 4) |
| 6 | Safezone | deny |
| 7 | Enemy factions | allow |
| 8 | Warzone | allow |
| 9 | Wilderness | allow |
| 10 | Anything else (neutral factions, factionless players in claimed land) | `allow-neutral-kills` (allow) |

Administrative kills, protected worlds and kill-farming cooldowns are DrugRunCore checks. This policy never sees them.

```yaml
loot-keys:
  faction-policy:
    prevent-same-faction-kills: true
    prevent-allied-faction-kills: true
    treat-truce-as-ally: true
    allow-enemy-faction-kills: true
    allow-neutral-kills: true
    allow-wilderness-pvp: true
    allow-warzone-pvp: true
    deny-in-safezones: true
    territory-overrides:
      downtown: DENY
```

### Other policy queries

`relation(UUID, UUID)` → `FactionRelation`, `raidStateAt(Location)` → `RaidState` (fails closed to `SERVER_PROTECTED`), `shieldedAt(Location)`.

## Public API

`DrugRunTerritoriesApi` is also registered with `ServicesManager`: territories, territory at a location, a player's bonus multiplier, faction XP and reputation, and the policy above.
