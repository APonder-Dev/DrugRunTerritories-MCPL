/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.progression.ProgressionRepository;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.storage.DataStore;
import dev.aponder.drugrun.territories.storage.Dialect;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Service-level capture tests with real SQLite storage (spec tests 13, 19, 22-25, 64). */
class TerritoryServiceTest {

    @TempDir
    Path dir;
    private HikariDataStore sqlite;
    private FlakyStore db;
    private final FakeFactions factions = new FakeFactions();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final Map<String, Map<String, Integer>> presence = new HashMap<>();
    private final List<CaptureEvent> events = new ArrayList<>();
    private ProgressionService progression;
    private TerritoryService service;
    private Settings settings;

    /** Wraps the real store and can be told to fail. */
    static final class FlakyStore implements DataStore {
        final DataStore delegate;
        final AtomicBoolean failing = new AtomicBoolean();

        FlakyStore(DataStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Dialect dialect() {
            return delegate.dialect();
        }

        @Override
        public int queryTimeoutSeconds() {
            return delegate.queryTimeoutSeconds();
        }

        @Override
        public String table(String name) {
            return delegate.table(name);
        }

        @Override
        public <T> CompletableFuture<T> query(SqlWork<T> work) {
            return failing.get() ? CompletableFuture.failedFuture(new SQLException("database down")) : delegate.query(work);
        }

        @Override
        public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
            return failing.get() ? CompletableFuture.failedFuture(new SQLException("database down")) : delegate.transaction(work);
        }

        @Override
        public boolean healthy() {
            return !failing.get() && delegate.healthy();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static TerritoryDefinition fast(String id, int x, Map<BonusType, Double> bonuses) {
        TerritoryDefinition d = Fixtures.territory(id, x, 0, bonuses);
        return new TerritoryDefinition(d.id(), d.displayName(), d.type(), true, d.region(), d.captureZone(),
                new TerritoryDefinition.CaptureOverrides(Duration.ofSeconds(10), 2, null, Duration.ofMinutes(1)), d.allowedFactions(),
                d.schedule(), d.protection(), d.pvp(), d.bonuses(), d.income(), d.rewards());
    }

    @BeforeEach
    void setUp() {
        sqlite = Fixtures.sqlite(dir);
        db = new FlakyStore(sqlite);
        settings = Fixtures.settings();
        factions.add("1", "Alpha");
        factions.add("2", "Bravo");
        progression = new ProgressionService(new ProgressionRepository(db), settings.progression(), 2, Fixtures.LOG);
        progression.load(Map.of(), List.of());
        service = newService(List.of(fast("a", 0, Map.of(BonusType.DEALER_SELL, 1.1)), fast("b", 100, Map.of(BonusType.DEALER_SELL, 1.1)),
                fast("c", 200, Map.of())));
        service.initialize(Map.of());
    }

    private TerritoryService newService(List<TerritoryDefinition> defs) {
        TerritoryService s = new TerritoryService(settings, new TerritoryRegistry(defs), new TerritoryRepository(db), factions, progression,
                (def, now) -> presence.getOrDefault(def.id(), Map.of()), Runnable::run, clock::get, () -> LocalTime.NOON, Fixtures.LOG);
        s.addListener((def, event, state) -> events.add(event));
        return s;
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            clock.addAndGet(1000);
            service.tick();
        }
    }

    private void captureAll(String territory, String faction) {
        presence.put(territory, Map.of(faction, 2));
        ticks(12);
        presence.remove(territory);
    }

    @Test
    @DisplayName("13/19: a capture completes through the service and is persisted")
    void captureCompletes() {
        captureAll("a", "1");
        TerritoryState s = service.state("a").orElseThrow();
        assertEquals(CaptureState.CONTROLLED, s.state());
        assertEquals("1", s.ownerFactionId());
        assertTrue(events.stream().anyMatch(e -> e instanceof CaptureEvent.Completed));
        Fixtures.eventually(() -> "1".equals(new TerritoryRepository(db).loadAll().join().get("a").ownerFactionId()));
    }

    @Test
    @DisplayName("22: the territory limit (2) stops a third capture")
    void limit() {
        captureAll("a", "1");
        captureAll("b", "1");
        presence.put("c", Map.of("1", 2));
        ticks(15);
        assertEquals(CaptureState.AVAILABLE, service.state("c").orElseThrow().state());
        assertEquals(2, service.ownedBy("1").size());
    }

    @Test
    @DisplayName("Bonuses from several owned territories stack within the cap")
    void bonusStacking() {
        assertEquals(1.0, service.bonusMultiplier("1", BonusType.DEALER_SELL), 1e-9);
        captureAll("a", "1");
        assertEquals(1.1, service.bonusMultiplier("1", BonusType.DEALER_SELL), 1e-9);
        captureAll("b", "1");
        assertEquals(1.21, service.bonusMultiplier("1", BonusType.DEALER_SELL), 1e-9);
        assertEquals(1.0, service.bonusMultiplier("2", BonusType.DEALER_SELL), 1e-9);
    }

    @Test
    @DisplayName("64/23: restart during a capture resumes from the saved progress")
    void restartDuringCapture() {
        presence.put("a", Map.of("1", 2));
        ticks(6);
        service.saveAll().join();
        double saved = service.state("a").orElseThrow().progressSeconds();
        assertTrue(saved > 0);
        TerritoryService restarted = newService(service.registry().all());
        restarted.initialize(new TerritoryRepository(db).loadAll().join());
        TerritoryState s = restarted.state("a").orElseThrow();
        assertEquals(CaptureState.CAPTURING, s.state());
        assertEquals(saved, s.progressSeconds(), 1e-9);
    }

    @Test
    @DisplayName("Restart grace holds a restored capture while its players reconnect, but not a capture started after the restart")
    void restartGraceOnlyForRestoredCaptures() {
        presence.put("a", Map.of("1", 2));
        ticks(6);
        service.saveAll().join();
        double saved = service.state("a").orElseThrow().progressSeconds();
        presence.clear();
        TerritoryService restarted = newService(service.registry().all());
        restarted.initialize(new TerritoryRepository(db).loadAll().join());
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(1_000);
            restarted.tick();
        }
        assertEquals(saved, restarted.state("a").orElseThrow().progressSeconds(), 1e-9, "held during the grace");
        // The capturers come back and complete; a new capture after that gets no grace.
        presence.put("a", Map.of("1", 2));
        for (int i = 0; i < 700 && restarted.state("a").orElseThrow().state() != CaptureState.CONTROLLED; i++) {
            clock.addAndGet(1_000);
            restarted.tick();
        }
        assertEquals(CaptureState.CONTROLLED, restarted.state("a").orElseThrow().state());
        restarted.reset("a");
        presence.put("a", Map.of("2", 2));
        clock.addAndGet(1_000);
        restarted.tick();
        clock.addAndGet(1_000);
        restarted.tick();
        assertEquals(CaptureState.CAPTURING, restarted.state("a").orElseThrow().state());
        presence.clear();
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(1_000);
            restarted.tick();
        }
        assertEquals(CaptureState.AVAILABLE, restarted.state("a").orElseThrow().state(), "the new capture drains normally");
    }

    @Test
    @DisplayName("Ownership that expired while the server was down is released on the first tick")
    void expiredDuringDowntime() {
        captureAll("a", "1");
        service.saveAll().join();
        clock.addAndGet(Duration.ofHours(25).toMillis());
        TerritoryService restarted = newService(service.registry().all());
        restarted.initialize(new TerritoryRepository(db).loadAll().join());
        restarted.tick();
        assertFalse(restarted.state("a").orElseThrow().owned());
    }

    @Test
    @DisplayName("24: faction disband releases its territories; a missed disband is caught by the next tick")
    void disband() {
        captureAll("a", "1");
        captureAll("b", "2");
        service.factionRemoved("1");
        assertFalse(service.state("a").orElseThrow().owned());
        factions.factions.remove("2");
        ticks(1);
        assertFalse(service.state("b").orElseThrow().owned());
        assertTrue(events.stream().filter(e -> e instanceof CaptureEvent.Revoked).count() >= 2);
    }

    @Test
    @DisplayName("25: database failure keeps state in memory and retries the save later")
    void databaseFailure() {
        db.failing.set(true);
        captureAll("a", "1");
        assertEquals("1", service.state("a").orElseThrow().ownerFactionId(), "gameplay continues during the outage");
        db.failing.set(false);
        service.saveDirty().join();
        Fixtures.eventually(() -> {
            TerritoryState stored = new TerritoryRepository(db).loadAll().join().get("a");
            return stored != null && "1".equals(stored.ownerFactionId());
        });
    }

    @Test
    @DisplayName("Faction data unavailable: capture ticks are skipped (fail closed)")
    void factionsUnavailable() {
        presence.put("a", Map.of("1", 2));
        factions.available = false;
        ticks(20);
        assertEquals(CaptureState.AVAILABLE, service.state("a").orElseThrow().state());
    }

    @Test
    @DisplayName("Admin reset and force-capture go through the service")
    void admin() {
        service.forceCapture("c", "2");
        assertEquals("2", service.state("c").orElseThrow().ownerFactionId());
        service.reset("c");
        assertEquals(CaptureState.AVAILABLE, service.state("c").orElseThrow().state());
    }

    @Test
    @DisplayName("Server lag cannot fast-forward a capture")
    void lagCapped() {
        presence.put("a", Map.of("1", 2));
        ticks(1);
        clock.addAndGet(300_000);
        service.tick();
        assertTrue(service.state("a").orElseThrow().progressSeconds() <= 2.0 + 1e-9);
    }
}
