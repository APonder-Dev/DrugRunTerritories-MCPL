/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.audit.AuditLog;
import dev.aponder.drugrun.territories.economy.PayoutRepository;
import dev.aponder.drugrun.territories.progression.FactionProgress;
import dev.aponder.drugrun.territories.progression.ProgressDelta;
import dev.aponder.drugrun.territories.progression.ProgressionRepository;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.CaptureState;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SQLite integration tests (spec tests 23, 61-65). */
class StorageTest {

    @TempDir
    Path dir;
    private HikariDataStore db;

    @BeforeEach
    void open() {
        db = Fixtures.sqlite(dir);
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    @DisplayName("61: migrations are idempotent and record the schema version")
    void migrations() {
        assertEquals(2, db.transaction(c -> Migrations.migrate(c, db, Fixtures.LOG)).join());
        assertEquals(2, db.transaction(c -> Migrations.migrate(c, db, Fixtures.LOG)).join());
        assertTrue(db.healthy());
    }

    @Test
    @DisplayName("A database from a newer plugin version is refused")
    void newerSchemaRefused() {
        db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO drt_schema_version (version, applied_at) VALUES (99, 0)")) {
                return ps.executeUpdate();
            }
        }).join();
        assertThrows(CompletionException.class, () -> db.transaction(c -> Migrations.migrate(c, db, Fixtures.LOG)).join());
    }

    @Test
    @DisplayName("23: territory state survives a restart (close and reopen)")
    void territoryRoundTrip() {
        TerritoryState s = new TerritoryState("docks", CaptureState.CONTROLLED, "7", 1000, 2000, 1500, null, 0, UUID.randomUUID(), false);
        TerritoryRepository repo = new TerritoryRepository(db);
        repo.save(s).join();
        repo.save(s.withProgress(3)).join();
        db.close();
        db = Fixtures.sqlite(dir);
        Map<String, TerritoryState> loaded = new TerritoryRepository(db).loadAll().join();
        assertEquals(s.withProgress(3), loaded.get("docks"));
        new TerritoryRepository(db).delete("docks").join();
        assertTrue(new TerritoryRepository(db).loadAll().join().isEmpty());
    }

    @Test
    @DisplayName("63: concurrent progression increments add up")
    void concurrentProgress() {
        ProgressionRepository repo = new ProgressionRepository(db);
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            all.add(repo.add("f1", ProgressDelta.capture(10, 1)));
        }
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();
        FactionProgress p = repo.loadAll().join().get("f1");
        assertEquals(500, p.xp());
        assertEquals(50, p.captures());
        repo.delete("f1").join();
        assertFalse(repo.loadAll().join().containsKey("f1"));
    }

    @Test
    @DisplayName("63/65: a payout key can be claimed exactly once, even concurrently and after restarts")
    void payoutIdempotency() {
        PayoutRepository repo = new PayoutRepository(db);
        List<CompletableFuture<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            claims.add(repo.claim("docks:own:1", "docks", "own", 1, "f1", 250));
        }
        long winners = claims.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();
        assertEquals(1, winners);
        // Restart while PENDING: becomes UNKNOWN and cannot be claimed again.
        assertEquals(1, repo.markPendingUnknown().join());
        assertFalse(repo.claim("docks:own:1", "docks", "own", 1, "f1", 250).join());
        assertEquals(PayoutRepository.Status.UNKNOWN, repo.recent(null, 5).join().getFirst().status());
        assertEquals(Map.of("own", 1), repo.claimedPeriods().join());
    }

    @Test
    @DisplayName("Completing a payout only changes PENDING rows")
    void completeOnlyPending() {
        PayoutRepository repo = new PayoutRepository(db);
        repo.claim("k", "t", "o", 1, "f", 10).join();
        repo.complete("k", PayoutRepository.Status.PAID, "ok").join();
        repo.complete("k", PayoutRepository.Status.FAILED, "late").join();
        assertEquals(PayoutRepository.Status.PAID, repo.recent(PayoutRepository.Status.PAID, 5).join().getFirst().status());
    }

    @Test
    @DisplayName("Audit entries are stored and filtered by action prefix")
    void audit() {
        AuditLog log = new AuditLog(db, Fixtures.LOG);
        log.record("Admin", "admin.territory.create", "docks", "x").join();
        log.record("SYSTEM", "economy.income.paid", "k", "y").join();
        assertEquals(1, log.recent("admin.", 10).join().size());
        assertEquals(2, log.recent("", 10).join().size());
        assertEquals(2, log.recent("%", 10).join().size(), "a wildcard prefix is stripped, not interpreted");
    }

    @Test
    @DisplayName("62: an unreachable MySQL server fails the startup load instead of hanging")
    void connectionFailure() {
        StorageSettings bad = new StorageSettings(Dialect.MYSQL, "x.db", "127.0.0.1", 1, "db", "u", "p", "drt_", 1,
                Duration.ofMillis(500), 1);
        assertThrows(RuntimeException.class, () -> new HikariDataStore(bad, dir, 1, 16, Fixtures.LOG));
    }

    @Test
    @DisplayName("69: closing the store rejects new work instead of running it")
    void closeRejects() {
        db.close();
        CompletableFuture<Integer> f = db.query(c -> 1);
        assertThrows(CompletionException.class, f::join);
        db = Fixtures.sqlite(dir);
    }

    @Test
    @DisplayName("Dialect SQL builders")
    void dialect() {
        assertTrue(Dialect.SQLITE.upsert("t", List.of("id"), List.of("id", "v")).contains("ON CONFLICT(id) DO UPDATE SET v = excluded.v"));
        assertTrue(Dialect.MYSQL.upsert("t", List.of("id"), List.of("id", "v")).contains("ON DUPLICATE KEY UPDATE v = VALUES(v)"));
        assertEquals("INSERT IGNORE", Dialect.MARIADB.insertIgnore());
    }

    @Test
    @DisplayName("61: a version-1 database upgrades to version 2 and keeps its data")
    void upgradeFromV1(@TempDir Path oldDir) {
        StorageSettings cfg = new StorageSettings(Dialect.SQLITE, "old.db", "", 0, "", "", "", "drt_", 1, Duration.ofSeconds(5), 5);
        HikariDataStore old = new HikariDataStore(cfg, oldDir, 1, 100, Fixtures.LOG);
        try {
            old.transaction(c -> {
                try (java.sql.Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE drt_schema_version (version INT NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL)");
                    for (String sql : Migrations.ALL.getFirst().statements().apply(old)) {
                        s.execute(sql);
                    }
                    s.execute("INSERT INTO drt_schema_version (version, applied_at) VALUES (1, 0)");
                    s.execute("INSERT INTO drt_faction_progress (faction_id, xp, updated_at) VALUES ('7', 1234, 0)");
                }
                return null;
            }).join();
            assertEquals(2, old.transaction(c -> Migrations.migrate(c, old, Fixtures.LOG)).join());
            FactionProgress p = new ProgressionRepository(old).loadAll().join().get("7");
            assertEquals(1234, p.xp());
            assertEquals(0, p.itemsSold());
        } finally {
            old.close();
        }
    }
}
