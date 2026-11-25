/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Faction progression (spec section 9, tests 49-50 statistics side). */
class ProgressionServiceTest {

    @TempDir
    Path dir;
    private HikariDataStore db;
    private ProgressionRepository repo;
    private ProgressionService service;
    private Settings.Progression cfg;

    @BeforeEach
    void setUp() {
        db = Fixtures.sqlite(dir);
        repo = new ProgressionRepository(db);
        cfg = Fixtures.settings().progression();
        service = new ProgressionService(repo, cfg, 2, Fixtures.LOG);
        service.load(Map.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    @DisplayName("XP is kept in memory and persisted as increments")
    void addAndPersist() {
        service.add("f", ProgressDelta.capture(500, 25));
        service.add("f", ProgressDelta.defense(250, 10));
        assertEquals(750, service.get("f").xp());
        Fixtures.eventually(() -> repo.loadAll().join().getOrDefault("f", FactionProgress.empty("f")).xp() == 750);
        assertEquals(1, repo.loadAll().join().get("f").defenses());
    }

    @Test
    @DisplayName("Levels unlock territory slots, capped by the absolute maximum")
    void territoryLimit() {
        assertEquals(2, service.territoryLimit("f"));
        service.add("f", ProgressDelta.xp(2500, 0));
        assertEquals(3, service.territoryLimit("f"));
        service.add("f", ProgressDelta.xp(100_000, 0));
        assertEquals(3, service.territoryLimit("f"), "absolute-max-territories is 3");
    }

    @Test
    @DisplayName("Raid victories against the same defender are rate-limited")
    void raidCooldown() {
        long now = 10_000_000L;
        assertTrue(service.tryRaidVictory("a", "d", now));
        assertFalse(service.tryRaidVictory("a", "d", now + 1000));
        assertTrue(service.tryRaidVictory("a", "e", now + 1000));
        assertTrue(service.tryRaidVictory("a", "d", now + cfg.raidVictoryCooldown().toMillis()));
        assertEquals(3, service.get("a").raidVictories());
        assertEquals(900, service.get("a").xp());
    }

    @Test
    @DisplayName("49: production milestones grant XP every N harvests")
    void harvestMilestone() {
        for (int i = 0; i < cfg.productionMilestoneHarvests() * 2; i++) {
            service.recordHarvest("f");
        }
        assertEquals(cfg.productionMilestoneHarvests() * 2L, service.get("f").harvests());
        assertEquals(cfg.productionMilestoneXp() * 2, service.get("f").xp());
    }

    @Test
    @DisplayName("50: paid sales grant XP each time a milestone of items sold is crossed")
    void saleMilestones() {
        service.recordSale("f", 499, 1000);
        assertEquals(0, service.get("f").xp());
        service.recordSale("f", 2, 10);
        assertEquals(100, service.get("f").xp());
        service.recordSale("f", 1000, 5000); // 501 -> 1501 crosses the 1000 and 1500 milestones
        assertEquals(1501, service.get("f").itemsSold());
        assertEquals(300, service.get("f").xp());
        assertEquals(6010.0, service.get("f").salesValue(), 1e-9);
        service.recordSale("f", 0, 10);
        service.recordSale("f", 5, Double.NaN);
        assertEquals(1501, service.get("f").itemsSold(), "empty or invalid sales are ignored");
        Fixtures.eventually(() -> repo.loadAll().join().getOrDefault("f", FactionProgress.empty("f")).itemsSold() == 1501);
    }

    @Test
    @DisplayName("Processing milestones grant XP every N collected jobs")
    void processingMilestones() {
        for (int i = 0; i < cfg.processingMilestoneJobs(); i++) {
            service.recordProcessing("f");
        }
        assertEquals(cfg.processingMilestoneJobs(), service.get("f").jobsProcessed());
        assertEquals(cfg.processingMilestoneXp(), service.get("f").xp());
    }

    @Test
    @DisplayName("Disabled progression still counts statistics but grants no XP")
    void disabled() {
        Settings.Progression off = new Settings.Progression(false, 500, 250, 300, 50, 100, 1, 500, 100, 25, 50, 25, 10, 15,
                cfg.raidVictoryCooldown(), cfg.levels(), 3);
        service.reconfigure(off, 2);
        service.add("f", ProgressDelta.capture(500, 25));
        assertEquals(0, service.get("f").xp());
        assertEquals(1, service.get("f").captures());
    }

    @Test
    @DisplayName("Admin reset removes progression; leaderboard orders by metric")
    void resetAndTop() {
        service.add("a", ProgressDelta.xp(10, 0));
        service.add("b", ProgressDelta.xp(30, 0));
        service.add("c", ProgressDelta.xp(20, 0));
        assertEquals(List.of("b", "c", "a"), service.top(FactionProgress::xp, 10).stream().map(FactionProgress::factionId).toList());
        service.reset("b");
        assertEquals(0, service.get("b").xp());
        assertEquals(2, service.top(FactionProgress::xp, 10).size());
    }

    @Test
    @DisplayName("Negative deltas are impossible")
    void negativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressDelta(-1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ProgressDelta.income(Double.NaN));
    }
}
