/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.support;

import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.config.SettingsLoader;
import dev.aponder.drugrun.territories.storage.Dialect;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.storage.Migrations;
import dev.aponder.drugrun.territories.storage.StorageSettings;
import dev.aponder.drugrun.territories.territory.CaptureRules;
import dev.aponder.drugrun.territories.territory.CaptureZone;
import dev.aponder.drugrun.territories.territory.Region;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryType;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** Shared test data. */
public final class Fixtures {

    public static final Logger LOG = Logger.getLogger("DrugRunTerritoriesTest");

    private Fixtures() {
    }

    /** @return the bundled resource parsed as YAML */
    public static YamlConfiguration bundled(String name) {
        try (Reader r = new InputStreamReader(Fixtures.class.getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(r);
            return yaml;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return settings from the bundled config.yml */
    public static Settings settings() {
        SettingsLoader.Result r = SettingsLoader.load(bundled("config.yml"));
        if (!r.ok()) {
            throw new IllegalStateException("Bundled config.yml invalid: " + r.errors());
        }
        return r.settings();
    }

    /** Capture rules: 60 s capture, 2 players, PAUSE, decay 1/s, 24 h ownership, 60 min cooldown, challenges on. */
    public static CaptureRules rules() {
        return new CaptureRules(Duration.ofSeconds(60), 2, Settings.ContestedBehavior.PAUSE, 1.0, true, Duration.ofHours(24),
                Duration.ofMinutes(60), true);
    }

    public static CaptureRules rules(Settings.ContestedBehavior behavior, Duration cooldown, boolean decayWhenAbandoned) {
        return new CaptureRules(Duration.ofSeconds(60), 2, behavior, 1.0, decayWhenAbandoned, Duration.ofHours(24), cooldown, true);
    }

    /** A 32x32 territory in "world" at the given block offset, with optional bonuses. */
    public static TerritoryDefinition territory(String id, int x, int z, Map<BonusType, Double> bonuses) {
        Region region = Region.of("world", x, 0, z, x + 31, 255, z + 31);
        return new TerritoryDefinition(id, "<white>" + id + "</white>", TerritoryType.CUSTOM, true, region,
                new CaptureZone(x + 16, 64, z + 16, 8), TerritoryDefinition.CaptureOverrides.NONE, Set.of(), List.of(),
                TerritoryDefinition.Protection.ARENA, TerritoryDefinition.PvpRule.DEFAULT, bonuses, null,
                TerritoryDefinition.Rewards.NONE);
    }

    public static TerritoryDefinition withIncome(TerritoryDefinition d, double amount, Duration interval, int max) {
        return new TerritoryDefinition(d.id(), d.displayName(), d.type(), d.enabled(), d.region(), d.captureZone(), d.overrides(),
                d.allowedFactions(), d.schedule(), d.protection(), d.pvp(), d.bonuses(),
                new TerritoryDefinition.Income(amount, interval, max), d.rewards());
    }

    /** Opens a migrated SQLite store in {@code dir}. */
    public static HikariDataStore sqlite(Path dir) {
        StorageSettings s = new StorageSettings(Dialect.SQLITE, "test.db", "", 0, "", "", "", "drt_", 1, Duration.ofSeconds(5), 5);
        HikariDataStore store = new HikariDataStore(s, dir, 1, 1000, LOG);
        store.transaction(c -> Migrations.migrate(c, store, LOG)).join();
        return store;
    }

    /** Waits up to five seconds for {@code condition}. */
    public static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Condition not met within 5 seconds");
    }
}
