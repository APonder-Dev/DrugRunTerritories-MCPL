/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Versioned schema migrations. Each migration runs once, in its own transaction, and records its
 * version in {@code <prefix>schema_version}. Never edit a released migration: add a new one.
 */
public final class Migrations {

    /** One schema step. {@code statements} receives the store, for table names and dialect. */
    public record Migration(int version, String description, Function<DataStore, List<String>> statements) {
    }

    /** All migrations, oldest first. */
    public static final List<Migration> ALL = List.of(
            new Migration(1, "initial schema", Migrations::v1),
            new Migration(2, "sale and processing statistics", Migrations::v2));

    private Migrations() {
    }

    private static List<String> v1(DataStore db) {
        Dialect d = db.dialect();
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + db.table("territory_state") + " ("
                        + "territory_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + "state VARCHAR(16) NOT NULL,"
                        + "owner_faction VARCHAR(64) NULL,"
                        + "owner_since BIGINT NOT NULL DEFAULT 0,"
                        + "owned_until BIGINT NOT NULL DEFAULT 0,"
                        + "cooldown_until BIGINT NOT NULL DEFAULT 0,"
                        + "capturing_faction VARCHAR(64) NULL,"
                        + "progress DOUBLE NOT NULL DEFAULT 0,"
                        + "ownership_id VARCHAR(36) NULL,"
                        + "updated_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + db.table("faction_progress") + " ("
                        + "faction_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + "xp BIGINT NOT NULL DEFAULT 0,"
                        + "reputation BIGINT NOT NULL DEFAULT 0,"
                        + "captures INT NOT NULL DEFAULT 0,"
                        + "defenses INT NOT NULL DEFAULT 0,"
                        + "raid_victories INT NOT NULL DEFAULT 0,"
                        + "control_seconds BIGINT NOT NULL DEFAULT 0,"
                        + "harvests BIGINT NOT NULL DEFAULT 0,"
                        + "income_earned DOUBLE NOT NULL DEFAULT 0,"
                        + "updated_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + db.table("income_payouts") + " ("
                        + "payout_key VARCHAR(160) NOT NULL PRIMARY KEY,"
                        + "territory_id VARCHAR(64) NOT NULL,"
                        + "ownership_id VARCHAR(36) NOT NULL,"
                        + "period_index INT NOT NULL,"
                        + "faction_id VARCHAR(64) NOT NULL,"
                        + "amount DOUBLE NOT NULL,"
                        + "status VARCHAR(16) NOT NULL,"
                        + "detail VARCHAR(255) NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "updated_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + db.table("raid_victories") + " ("
                        + "attacker VARCHAR(64) NOT NULL,"
                        + "defender VARCHAR(64) NOT NULL,"
                        + "occurred_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (attacker, defender, occurred_at))",
                "CREATE TABLE IF NOT EXISTS " + db.table("audit_log") + " ("
                        + "id " + d.autoIncrementPrimaryKey() + ","
                        + "occurred_at BIGINT NOT NULL,"
                        + "actor VARCHAR(64) NOT NULL,"
                        + "action VARCHAR(64) NOT NULL,"
                        + "target VARCHAR(128) NULL,"
                        + "details VARCHAR(1024) NULL)",
                "CREATE TABLE IF NOT EXISTS " + db.table("capture_history") + " ("
                        + "id " + d.autoIncrementPrimaryKey() + ","
                        + "territory_id VARCHAR(64) NOT NULL,"
                        + "faction_id VARCHAR(64) NULL,"
                        + "event VARCHAR(32) NOT NULL,"
                        + "occurred_at BIGINT NOT NULL)");
    }

    private static List<String> v2(DataStore db) {
        String t = db.table("faction_progress");
        return List.of(
                "ALTER TABLE " + t + " ADD COLUMN items_sold BIGINT NOT NULL DEFAULT 0",
                "ALTER TABLE " + t + " ADD COLUMN sales_value DOUBLE NOT NULL DEFAULT 0",
                "ALTER TABLE " + t + " ADD COLUMN jobs_processed BIGINT NOT NULL DEFAULT 0");
    }

    /**
     * Applies every pending migration. Runs on the database pool.
     *
     * @return the schema version after migrating
     */
    public static int migrate(Connection connection, DataStore db, Logger logger) throws SQLException {
        String versionTable = db.table("schema_version");
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + versionTable + " (version INT NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL)");
        }
        int current = 0;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(version) FROM " + versionTable)) {
            if (rs.next()) {
                current = rs.getInt(1);
            }
        }
        int latest = ALL.getLast().version();
        if (current > latest) {
            throw new SQLException("Database schema version " + current + " is newer than this plugin supports (" + latest
                    + "). Refusing to start to avoid damaging data.");
        }
        boolean previous = connection.getAutoCommit();
        for (Migration migration : ALL) {
            if (migration.version() <= current) {
                continue;
            }
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                for (String sql : migration.statements().apply(db)) {
                    s.execute(sql);
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO " + versionTable + " (version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, migration.version());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                connection.commit();
                current = migration.version();
                logger.info("Applied database migration " + migration.version() + ": " + migration.description());
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
        return current;
    }
}
