/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

import dev.aponder.drugrun.territories.storage.DataStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Faction progression storage. Changes are written as increments ({@code xp = xp + ?}), so
 * concurrent writes add up instead of overwriting each other.
 */
public final class ProgressionRepository {

    private final DataStore db;

    public ProgressionRepository(DataStore db) {
        this.db = db;
    }

    public CompletableFuture<Map<String, FactionProgress>> loadAll() {
        return db.query(c -> {
            Map<String, FactionProgress> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT faction_id, xp, reputation, captures, defenses, raid_victories,"
                    + " control_seconds, harvests, income_earned, items_sold, sales_value, jobs_processed FROM "
                    + db.table("faction_progress"))) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        FactionProgress p = new FactionProgress(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                                rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getDouble(9), rs.getLong(10),
                                rs.getDouble(11), rs.getLong(12));
                        out.put(p.factionId(), p);
                    }
                }
            }
            return out;
        });
    }

    /** Adds {@code delta} to the faction's stored totals in one transaction. */
    public CompletableFuture<Void> add(String factionId, ProgressDelta delta) {
        return db.transaction(c -> {
            ensureRow(c, factionId);
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("faction_progress") + " SET xp = xp + ?,"
                    + " reputation = reputation + ?, captures = captures + ?, defenses = defenses + ?,"
                    + " raid_victories = raid_victories + ?, control_seconds = control_seconds + ?, harvests = harvests + ?,"
                    + " income_earned = income_earned + ?, items_sold = items_sold + ?, sales_value = sales_value + ?,"
                    + " jobs_processed = jobs_processed + ?, updated_at = ? WHERE faction_id = ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setLong(1, delta.xp());
                ps.setLong(2, delta.reputation());
                ps.setInt(3, delta.captures());
                ps.setInt(4, delta.defenses());
                ps.setInt(5, delta.raidVictories());
                ps.setLong(6, delta.controlSeconds());
                ps.setLong(7, delta.harvests());
                ps.setDouble(8, delta.income());
                ps.setLong(9, delta.itemsSold());
                ps.setDouble(10, delta.salesValue());
                ps.setLong(11, delta.jobsProcessed());
                ps.setLong(12, System.currentTimeMillis());
                ps.setString(13, factionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(String factionId) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("faction_progress") + " WHERE faction_id = ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, factionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** @return raid victories newer than {@code since}, as attacker/defender/time triples */
    public CompletableFuture<List<RaidVictoryRecord>> raidVictoriesSince(long since) {
        return db.query(c -> {
            List<RaidVictoryRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT attacker, defender, occurred_at FROM " + db.table("raid_victories")
                    + " WHERE occurred_at >= ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new RaidVictoryRecord(rs.getString(1), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> recordRaidVictory(RaidVictoryRecord record) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(db.dialect().insertIgnore() + " INTO " + db.table("raid_victories")
                    + " (attacker, defender, occurred_at) VALUES (?, ?, ?)")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, record.attacker());
                ps.setString(2, record.defender());
                ps.setLong(3, record.occurredAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void ensureRow(Connection c, String factionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertIgnore() + " INTO " + db.table("faction_progress")
                + " (faction_id, updated_at) VALUES (?, ?)")) {
            ps.setQueryTimeout(db.queryTimeoutSeconds());
            ps.setString(1, factionId);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** One stored raid victory. */
    public record RaidVictoryRecord(String attacker, String defender, long occurredAt) {
    }
}
