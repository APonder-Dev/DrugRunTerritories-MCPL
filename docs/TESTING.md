# Testing

## Result of the last run

`mvn clean verify` on 2026-11-24:

```
Tests run: 136, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Component | Version used |
|---|---|
| JDK | 25.0.3 |
| Maven | 3.9.11 |
| Paper API | 26.3.build.249-stable (compile + MockBukkit) |
| MockBukkit | mockbukkit-v26.3 4.124.0 |
| FactionsUUID API | 4.6.0 (`dev.kitteh:factions`), interfaces mocked with Mockito |
| DrugLab | 1.0.0 artifact built from the patched working tree with the extended API (real event, plant and growth-stage classes; static `DrugLabAPI` mocked). DrugLab's own suite: 16 tests, all passing |
| JUnit | Jupiter 6.1.3 (required by MockBukkit 4.124; the JUnit 5 Jupiter API) |
| Mockito | 5.24.0 |
| SQLite JDBC | 3.53.4.0 (real database files in temp folders) |
| MySQL Connector/J | 9.6.0 (connection-failure test only) |

Run it yourself: `mvn verify`. Reports: `target/surefire-reports`.

## Live Paper 26.3 run (2026-11-25/26)

Paper 26.3 build 259 with FactionsUUID 4.7.2, DrugLab (patched), Vault, WorldGuard 7.0.21 on FAWE, PlaceholderAPI, LuckPerms, EssentialsX and ViaVersion/ViaBackwards. The player tests used a mineflayer bot on a localhost offline-mode copy of the test server.

| Check | Result |
|---|---|
| Plugin startup, both migrations, every integration detected, clean shutdown | pass |
| `/drugrun` commands, `admin debug`, `admin reload`, `drugrun` PlaceholderAPI expansion | pass |
| TNT next to an arena block (control block outside destroyed) | pass, arena block and floor intact |
| Falling sand into the arena (control lands outside) | pass, blocked |
| Water flowing from outside into the arena (3 sources, bot nearby so chunks tick) | pass, flowed outside, kept out, every flow into the arena logged as cancelled |
| Bot pours a water bucket inside the arena / outside | pass, refused / placed |
| Bot mines a block inside the arena / outside | pass, kept / broken |
| DrugLab `/druglab selftest` with DrugRunTerritories listening | pass, 92/92 |

Water does not tick in force-loaded chunks without a player, so console-only water tests are not meaningful.

## What was not run

- **Live gameplay with real players:** captures, contests, BossBars, faction claims, dealer sales and Vault payouts on the live server.
- **Live MySQL/MariaDB.** Only SQLite was used for storage tests. MySQL was used only to prove an unreachable server fails startup.
- **Spark profiling.** `LoadTest` measures the capture loop in isolation. Profile on a staging server before launch.
- **WorldGuard** hook (`WorldGuardHook`) compiles against WorldGuard 7.0.11 but has no automated test.

## Specification test map

✅ executed and passing · ◐ partly covered (see note) · ⏭ skipped (reason given)

| # | Test | Status | Where |
|---|---|---|---|
| 1 | Faction membership | ✅ | `FactionsUuidProviderTest.membership` |
| 2 | Ownership lookup | ✅ | `FactionsUuidProviderTest.ownership` |
| 3 | Allied restrictions | ✅ | `FactionsUuidProviderTest.relations`, `CaptureEngineTest.challengeRestrictions`, `FactionKillPolicyTest.allied` |
| 4 | Enemy restrictions | ✅ | `FactionsUuidProviderTest.relations`, `FactionKillPolicyTest.enemy` |
| 5 | Wilderness | ✅ | `FactionsUuidProviderTest.ownership`, `FactionKillPolicyTest.wilderness`, `RaidPolicyTest` |
| 6 | Faction disbanding | ✅ | `FactionChangeListenerTest.disband`, `TerritoryServiceTest.disband` |
| 7 | Claim ownership changes | ✅ | `FactionChangeListenerTest.claimsInsideTerritoriesBlocked`, `DrugLabIntegrationTest.growthAfterClaimChange` |
| 8 | DTR vulnerability | ✅ | `FactionsUuidProviderTest.raidStates` |
| 9 | Shields | ✅ | `FactionsUuidProviderTest.raidStates` |
| 10 | Grace | ✅ | `FactionsUuidProviderTest.raidStates` |
| 11 | Territory creation | ✅ | `TerritoryDefinitionTest.roundTrip` |
| 12 | Territory deletion | ✅ | `TerritoryDefinitionTest.roundTrip`, `StorageTest.territoryRoundTrip` |
| 13 | Valid capture | ✅ | `CaptureEngineTest.validCapture`, `TerritoryServiceTest.captureCompletes` |
| 14 | Insufficient participants | ✅ | `CaptureEngineTest.insufficientParticipants` |
| 15 | Contested capture | ✅ | `CaptureEngineTest.contestedPauses`, `contestedDecays` |
| 16 | Player disconnect | ◐ | `CaptureEngineTest.disconnectDecays` (engine). The Bukkit gatherer is not tested on a server |
| 17 | Player death | ◐ | Same as 16. The dead-player filter in `BukkitParticipantSource.eligible` is untested |
| 18 | Competing factions | ✅ | `CaptureEngineTest.multipleFactions`, `twoFactionsNoStart` |
| 19 | Capture completion | ✅ | `CaptureEngineTest.validCapture` |
| 20 | Expiration | ✅ | `CaptureEngineTest.expiryAndCooldown`, `TerritoryServiceTest.expiredDuringDowntime` |
| 21 | Cooldown | ✅ | `CaptureEngineTest.expiryAndCooldown`, `challengeRestrictions` |
| 22 | Territory limits | ✅ | `CaptureEngineTest.limitEnforced`, `TerritoryServiceTest.limit` |
| 23 | Restart persistence | ✅ | `StorageTest.territoryRoundTrip`, `TerritoryServiceTest.restartDuringCapture` |
| 24 | Disband during ownership | ✅ | `CaptureEngineTest.disbandRevokes`, `TerritoryServiceTest.disband` |
| 25 | Recovery after database failure | ✅ | `TerritoryServiceTest.databaseFailure` |
| 26 | TNT vs protected claims | ✅ | `ProtectionListenerTest.ordinaryClaimBlocks`, `drugLabCrops` |
| 27 | TNT vs vulnerable claims | ✅ | same |
| 28 | TNT vs shielded claims | ✅ | `ProtectionListenerTest.drugLabCrops`, `RaidPolicyTest` |
| 29 | TNT across claim borders | ✅ | `ProtectionListenerTest.acrossBorder`, `plantUsesBase` |
| 30 | TNT vs arenas | ✅ | `ProtectionListenerTest.arenaExplosion` |
| 31 | Indirect block changes | ◐ | Fluids and pistons unit tested; water, falling blocks and TNT verified live on Paper 26.3. Fire, dispensers and tree growth not tested |
| 32 | Protected DrugLab crops | ✅ | `ProtectionListenerTest.drugLabCrops` |
| 33 | Vulnerable DrugLab crops | ✅ | same |
| 34 | Protected stations | ✅ | `ProtectionListenerTest.stationsAndFailClosed`, `DrugLabIntegrationTest.stationAccess` |
| 35 | Unprotected stations | ✅ | same |
| 36 | Authorized planting | ✅ | `DrugLabIntegrationTest.authorizedPlanting` |
| 37 | Unauthorized planting | ✅ | `DrugLabIntegrationTest.unauthorizedPlanting` |
| 38 | Multi-block across a border | ✅ | `DrugLabIntegrationTest.footprintCrossesBorder` |
| 39 | Growth after claim change | ✅ | `DrugLabIntegrationTest.growthAfterClaimChange` |
| 40 | Authorized harvesting | ✅ | `DrugLabIntegrationTest.harvesting` |
| 41 | Unauthorized harvesting | ✅ | same |
| 42 | Fertilizer permission | ✅ | `DrugLabIntegrationTest.fertilizer` |
| 43 | Dealer bonus eligibility | ✅ | `DrugLabIntegrationTest.dealerBonus`, `bonusFailsClosed` |
| 44 | Quality pricing preserved | ✅ | `dealerBonus` (bonus multiplies DrugLab's finished price) |
| 45 | Market demand preserved | ✅ | same |
| 46 | Failed transaction gives nothing | ✅ | `DrugLabIntegrationTest.cancelledTransaction` |
| 47 | Processing bonus eligibility | ✅ | `DrugLabIntegrationTest.modifierProvider`; DrugLab-side capping in DrugLab `ApiHookTest` |
| 48 | Station transaction recovery | ⏭ | Station jobs and outputs are DrugLab's. DrugRunTerritories doesn't touch them |
| 49 | Faction production statistics | ✅ | `DrugLabIntegrationTest.productionStats`, `ProgressionServiceTest.harvestMilestone` |
| 50 | Drug-sale progression | ✅ | `DrugLabIntegrationTest.saleStatistics`, `ProgressionServiceTest.saleMilestones` |
| 51 | Enemy kill eligible | ✅ | `FactionKillPolicyTest.enemy` |
| 52 | Same-faction denied | ✅ | `FactionKillPolicyTest.sameFaction` |
| 53 | Allied denied | ✅ | `FactionKillPolicyTest.allied` |
| 54 | Safezone denied | ✅ | `FactionKillPolicyTest.safezone` |
| 55 | Wilderness PvP eligible | ✅ | `FactionKillPolicyTest.wilderness` |
| 56 | Policy service unavailable | ✅ | `FactionKillPolicyTest.unavailable` |
| 57 | Generation delegated to DrugRunCore | ◐ | `ArchitectureTest.noLootKeyDuplication` proves no generation code here. DrugRunCore itself does not exist yet |
| 58 | No duplicate Loot Key implementation | ✅ | `ArchitectureTest.noLootKeyDuplication` |
| 59 | Consumption stays in DrugRunCore | ◐ | as 57 |
| 60 | Key disintegration after final use | ⏭ | DrugRunCore functionality; DrugRunCore not built |
| 61 | Database migration | ✅ | `StorageTest.migrations`, `newerSchemaRefused`, `upgradeFromV1` |
| 62 | Database connection failure | ✅ | `StorageTest.connectionFailure`, `TerritoryServiceTest.databaseFailure` |
| 63 | Concurrent reward processing | ✅ | `StorageTest.payoutIdempotency`, `concurrentProgress` |
| 64 | Restart during capture | ✅ | `TerritoryServiceTest.restartDuringCapture` |
| 65 | Restart during payout | ✅ | `IncomeServiceTest.crashDuringPayout`, `restartSafety` |
| 66 | Many territories | ✅ | `TerritoryDefinitionTest.manyTerritories`, `LoadTest` |
| 67 | Many active crops | ⏭ | Needs a running DrugLab with real plants |
| 68 | High player concurrency | ◐ | `LoadTest` simulates 200 contested zones. No live players |
| 69 | Plugin disable and reload | ◐ | `StorageTest.closeRejects` only. The full lifecycle needs a live server |
| 70 | Integration missing or disabled | ✅ | `FactionsUuidProviderTest.unavailable`, `TerritoryServiceTest.factionsUnavailable`, `ArchitectureTest.drugLabIsolated` |

Extra coverage: settings validation (`SettingsLoaderTest`), atomic config files (`LocalConfigFilesTest`), income caps, splits and expiry (`IncomeServiceTest`), progression levels and raid-victory cooldown (`ProgressionServiceTest`), schedules, overlap detection and registry bounds (`TerritoryDefinitionTest`), no NMS, reflection or TODOs (`ArchitectureTest`).

## Recommended live checks before launch

1. Start Paper 26.3 with FactionsUUID 4.6+, DrugLab, Vault and an economy, and PlaceholderAPI. Confirm the startup line lists every integration as on.
2. Two factions: capture, contest, challenge, defend; watch BossBars and announcements.
3. TNT cannon into a raidable claim with DrugLab crops; repeat with a shield active.
4. Downtown income across a restart; then `/drugrun admin economy audit all`.
5. `/papi parse me %drugrun_territory_docks_owner%`.
6. Spark profile during a busy capture.
