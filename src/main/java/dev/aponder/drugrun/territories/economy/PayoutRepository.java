/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.economy;

import dev.aponder.drugrun.territories.storage.DataStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Payout ledger. A payout is claimed by inserting its unique key before any money moves; a second
 * claim of the same key inserts nothing and is refused. That makes payouts idempotent across
 * retries, reloads and restarts.
 */
public final class PayoutRepository {

    /** Lifecycle of a payout row. */
    public enum Status {
        /** Claimed; money has not been confirmed. */
        PENDING,
        /** Money was deposited. */
        PAID,
        /** Not paid (no recipient, economy refused, ownership changed). Never retried. */
        FAILED,
        /**
         * Was PENDING when the server stopped, so whether money moved is unknown. Never retried,
         * to rule out double payment; listed by {@code /drugrun admin economy audit} for review.
         */
        UNKNOWN
    }

    /** One ledger row. */
    public record Payout(String key, String territoryId, String ownershipId, int periodIndex, String factionId, double amount,
                         Status status, String detail, long createdAt, long updatedAt) {
    }

    private final DataStore db;

    public PayoutRepository(DataStore db) {
        this.db = db;
    }

    /** @return {@code true} when this call claimed the key, {@code false} when it was already claimed */
    public CompletableFuture<Boolean> claim(String key, String territoryId, String ownershipId, int periodIndex,
                                            String factionId, double amount) {
        return db.query(c -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(db.dialect().insertIgnore() + " INTO " + db.table("income_payouts")
                    + " (payout_key, territory_id, ownership_id, period_index, faction_id, amount, status, detail, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, key);
                ps.setString(2, territoryId);
                ps.setString(3, ownershipId);
                ps.setInt(4, periodIndex);
                ps.setString(5, factionId);
                ps.setDouble(6, amount);
                ps.setString(7, Status.PENDING.name());
                ps.setLong(8, now);
                ps.setLong(9, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Moves a PENDING payout to its final status. Rows that are not PENDING are left alone. */
    public CompletableFuture<Void> complete(String key, Status status, String detail) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("income_payouts")
                    + " SET status = ?, detail = ?, updated_at = ? WHERE payout_key = ? AND status = ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, status.name());
                ps.setString(2, detail == null ? null : detail.length() > 250 ? detail.substring(0, 250) : detail);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, key);
                ps.setString(5, Status.PENDING.name());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Startup recovery: every PENDING row becomes UNKNOWN. @return how many were changed */
    public CompletableFuture<Integer> markPendingUnknown() {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("income_payouts")
                    + " SET status = ?, detail = ?, updated_at = ? WHERE status = ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, Status.UNKNOWN.name());
                ps.setString(2, "server stopped before the payout was confirmed");
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, Status.PENDING.name());
                return ps.executeUpdate();
            }
        });
    }

    /** @return highest claimed period index per ownership id (any status) */
    public CompletableFuture<Map<String, Integer>> claimedPeriods() {
        return db.query(c -> {
            Map<String, Integer> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ownership_id, MAX(period_index) FROM " + db.table("income_payouts")
                    + " GROUP BY ownership_id")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return out;
        });
    }

    /** @return the newest rows, optionally only those with {@code status} */
    public CompletableFuture<List<Payout>> recent(Status status, int limit) {
        return db.query(c -> {
            List<Payout> out = new ArrayList<>();
            String where = status == null ? "" : " WHERE status = ?";
            try (PreparedStatement ps = c.prepareStatement("SELECT payout_key, territory_id, ownership_id, period_index, faction_id,"
                    + " amount, status, detail, created_at, updated_at FROM " + db.table("income_payouts") + where
                    + " ORDER BY created_at DESC LIMIT ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                int i = 1;
                if (status != null) {
                    ps.setString(i++, status.name());
                }
                ps.setInt(i, Math.max(1, Math.min(limit, 200)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Status st;
                        try {
                            st = Status.valueOf(rs.getString(7));
                        } catch (IllegalArgumentException e) {
                            st = Status.UNKNOWN;
                        }
                        out.add(new Payout(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                                rs.getDouble(6), st, rs.getString(8), rs.getLong(9), rs.getLong(10)));
                    }
                }
            }
            return out;
        });
    }
}
