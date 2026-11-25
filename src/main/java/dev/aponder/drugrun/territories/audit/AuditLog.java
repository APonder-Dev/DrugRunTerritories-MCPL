/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.audit;

import dev.aponder.drugrun.territories.storage.DataStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only audit trail for administrative and economy actions. Each entry goes to the
 * database and to the server log, so nothing is lost if one of them is unavailable.
 */
public final class AuditLog {

    /** One stored entry. */
    public record Entry(long id, long occurredAt, String actor, String action, String target, String details) {
    }

    private static final int MAX_DETAILS = 1000;

    private final DataStore db;
    private final Logger logger;

    public AuditLog(DataStore db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * Records an action. Safe from any thread; the write happens on the database pool.
     *
     * @param actor player name, {@code CONSOLE} or {@code SYSTEM}
     */
    public CompletableFuture<Void> record(String actor, String action, String target, String details) {
        String safeDetails = details == null ? null : details.length() > MAX_DETAILS ? details.substring(0, MAX_DETAILS) : details;
        long now = System.currentTimeMillis();
        logger.info("[audit] " + actor + " " + action + (target == null ? "" : " " + target)
                + (safeDetails == null ? "" : " (" + safeDetails + ")"));
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("audit_log")
                    + " (occurred_at, actor, action, target, details) VALUES (?, ?, ?, ?, ?)")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setLong(1, now);
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setString(4, target);
                ps.setString(5, safeDetails);
                ps.executeUpdate();
            }
            return (Void) null;
        }).exceptionally(t -> {
            logger.log(Level.SEVERE, "Could not write audit entry " + action + " " + target, t);
            return null;
        });
    }

    /** @return the newest entries whose action starts with {@code actionPrefix} (all when empty) */
    public CompletableFuture<List<Entry>> recent(String actionPrefix, int limit) {
        return db.query(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, occurred_at, actor, action, target, details FROM "
                    + db.table("audit_log") + " WHERE action LIKE ? ORDER BY id DESC LIMIT ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, actionPrefix.replace("%", "") + "%");
                ps.setInt(2, Math.max(1, Math.min(limit, 200)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6)));
                    }
                }
            }
            return out;
        });
    }
}
