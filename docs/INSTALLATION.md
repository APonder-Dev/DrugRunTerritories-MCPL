# Installation

## Server requirements

| Plugin | Version | Required |
|---|---|---|
| Paper | 26.3 | yes |
| Java | 25 | yes |
| FactionsUUID | 4.6.0 or newer 4.x (`dev.kitteh.factions`) | yes |
| DrugLab | 1.0.0 | when territories grant drug bonuses (see below) |
| Vault + an economy plugin | 1.7.x | for Downtown income and money rewards |
| PlaceholderAPI | 2.11+ | for placeholders |
| WorldGuard | 7.0.x | optional; plant footprints then also respect WorldGuard regions |
| DrugRunCore | not released | optional for now |

LuckPerms, DeluxeMenus, BattlePass, ShopGUIPlus, PlayerVaultsX and Citizens are **not integrated** in 0.1.0. Permissions work with any permissions plugin. A DeluxeMenus example menu that uses the placeholders is in `examples/deluxemenus-territories.yml`.

## Install

1. Stop the server.
2. Copy `DrugRunTerritories-0.1.0-SNAPSHOT.jar` into `plugins/`.
3. Start the server. The plugin creates `plugins/DrugRunTerritories/` with `config.yml`, `messages.yml`, `territories.yml` and `territories.db`.
4. If startup fails, the console lists every configuration problem and the plugin disables itself. Nothing is half-enabled.
5. Set up the districts in-game (see [TERRITORIES.md](TERRITORIES.md)). They ship disabled so the placeholder coordinates never protect real land.

### DrugLab requirement

When any enabled territory grants `DEALER_SELL`, `PROCESSING_SPEED` or `CROP_GROWTH`, and `integrations.druglab.required-for-drug-bonuses` is `true` (the default), DrugRunTerritories refuses to start without DrugLab. To run without DrugLab, set that key to `false`. Drug bonuses then have no effect.

### Storage

SQLite is the default and needs no setup. For MySQL or MariaDB:

```yaml
storage:
  type: MYSQL        # or MARIADB
  host: db.example
  port: 3306
  database: drugrun
  username: drugrun
  password: "..."
```

The JDBC drivers come from the Paper server jar (SQLite: `org.sqlite.JDBC`, MySQL/MariaDB: `com.mysql.cj.jdbc.Driver`). Tables are created and migrated automatically. Passwords are never logged.

## Building from source

Prerequisites: JDK 25, Maven 3.9+, internet access for Maven dependencies.

DrugLab is not published to a Maven repository, so install it into your local repository once:

```powershell
cd ..\DrugLab
mvn install -DskipTests
```

Then build DrugRunTerritories with Maven 3.9+:

```powershell
mvn clean verify
```

This compiles, runs all tests, fails on any error, and produces `target/DrugRunTerritories-0.1.0-SNAPSHOT.jar`.

CI: `.github/workflows/build.yml` checks out DrugLab next to this repository, installs it, and runs `mvn verify`. For a private DrugLab repository, set the `DRUGLAB_TOKEN` secret (and `DRUGLAB_REPOSITORY` if it moves). CI only uploads test reports, not the plugin JAR, so build the JAR yourself until releases are published.

## Dependencies bundled in the jar

Only HikariCP is bundled, relocated to `dev.aponder.drugrun.territories.libs.hikari`. Paper, FactionsUUID, DrugLab, Vault, PlaceholderAPI, WorldGuard and the JDBC drivers are provided by the server and are **not** shaded.
