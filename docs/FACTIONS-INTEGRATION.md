# FactionsUUID integration

FactionsUUID stays **authoritative** for faction identity, membership, relations, claims, land protection, DTR, raidability, shields, grace and its own upgrades. DrugRunTerritories reads that data through its public API every time it needs it. It keeps no copy of faction data, never edits FactionsUUID files, and uses no reflection or NMS.

Verified against **FactionsUUID 4.6.0** (`dev.kitteh:factions`, Maven repository `https://dependency.download/releases`). FactionsUUID 4.x is a Paper plugin. DrugRunTerritories declares it in `paper-plugin.yml` with `load: BEFORE`, `required: true` and `join-classpath: true`.

## API used

| Need | FactionsUUID API |
|---|---|
| Player membership and rank | `FPlayers.fPlayers().has(uuid)`, `.get(uuid)`, `FPlayer#hasFaction()`, `#faction()`, `#role()` |
| Faction by id or tag | `Factions.factions().get(int)`, `.get(String)` |
| Land owner of a chunk | `Board.board().factionAt(new FLocation(world, chunkX, chunkZ))` |
| Wilderness, safezone, warzone | `Faction#isWilderness()`, `#isSafeZone()`, `#isWarZone()`, `#isNormal()` |
| Relations | `Participator#relationTo(Participator)` → `Relation` (MEMBER, ALLY, TRUCE, NEUTRAL, ENEMY) |
| Leader and members | `Faction#admin()`, `#size()`, `#membersOnlineAsPlayers()` |
| Raidability (DTR or power, per FactionsUUID's land-raid-control setting) | `Faction#isRaidable()` |
| Shields | `Faction#shieldActive()` |
| Server grace period | `Universe.universe().grace()` |
| Peaceful factions | `Faction#isPeaceful()` |
| Build, break, container access | `Faction#hasAccess(FPlayer, PermissibleActions.BUILD/DESTROY/CONTAINER, FLocation)`, `FPlayer#adminBypass()` |
| Events | `LandClaimEvent` (cancellable), `FactionDisbandEvent`, `FactionAutoDisbandEvent` |

## Raid states

`RaidState` is DrugRunTerritories' stable summary. It never overrides FactionsUUID. Precedence, first match wins:

1. Safezone or warzone → `SERVER_PROTECTED`
2. Wilderness → `WILDERNESS`
3. FactionsUUID grace running → `GRACE`
4. Shield active → `SHIELDED`
5. Peaceful → `PROTECTED`
6. `isRaidable()` → `VULNERABLE`
7. otherwise → `PROTECTED`

A shield or grace period always beats DTR 0: a faction at 0 DTR under a shield is not treated as vulnerable.

## Balance values (configure in FactionsUUID)

The proposed values below belong in **FactionsUUID's own configuration**. DrugRunTerritories doesn't implement a competing DTR or claim system. The exact file and key names were not verified here (no FactionsUUID installation was available), so none are assumed. After installing FactionsUUID, open its generated configuration and set the matching options. FactionsUUID's land-raid-control choice (DTR or power) is what `Faction#isRaidable()` reports.

| Proposed value | Look for in FactionsUUID's config |
|---|---|
| Maximum faction size 8 | the faction member limit |
| DTR land-raid control | `landRaidControl` system set to DTR |
| Starting DTR 4.0, loss 1.0 per member death | DTR start/max and per-death loss options |
| Regeneration delay 30 minutes | DTR regeneration/freeze-after-death option |
| Raidable at DTR ≤ 0 | the raidable threshold |
| New-season grace 60 hours | `/f grace` and the grace options |

Verify each option in the generated file. These names vary between FactionsUUID releases.

## Changes from FactionsUUID that DrugRunTerritories handles

| Change | Effect |
|---|---|
| A claim touching a territory region | Cancelled: territories are server-owned and never claimable |
| Faction disbanded (manual or automatic) | Its territories enter cooldown immediately and its capture attempts end. A disband missed while the plugin was offline is caught on the next capture tick |
| Member joins or leaves | Nothing to invalidate: membership is read live every capture tick, bonus and policy check |
| Relation change | Same: read live |
| FactionsUUID disabled or throwing | Every caller fails closed: no capture progress, no bonuses, no payouts, DrugLab actions denied, Loot Key policy answers `UNAVAILABLE` |
