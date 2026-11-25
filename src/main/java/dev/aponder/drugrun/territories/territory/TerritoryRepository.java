/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.storage.DataStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persists territory runtime state and capture history. All methods run on the database pool. */
public final class TerritoryRepository {

    private static final List<String> COLUMNS = List.of("territory_id", "state", "owner_faction", "owner_since", "owned_until",
            "cooldown_until", "capturing_faction", "progress", "ownership_id", "updated_at");

    private final DataStore db;

    public TerritoryRepository(DataStore db) {
        this.db = db;
    }

    /** @return every stored state keyed by territory id */
    public CompletableFuture<Map<String, TerritoryState>> loadAll() {
        return db.query(c -> {
            Map<String, TerritoryState> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT territory_id, state, owner_faction, owner_since, owned_until,"
                    + " cooldown_until, capturing_faction, progress, ownership_id FROM " + db.table("territory_state"))) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        CaptureState state;
                        try {
                            state = CaptureState.valueOf(rs.getString(2));
                        } catch (IllegalArgumentException e) {
                            state = CaptureState.AVAILABLE;
                        }
                        String ownership = rs.getString(9);
                        out.put(id, new TerritoryState(id, state, rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                                rs.getString(7), rs.getDouble(8), ownership == null ? null : UUID.fromString(ownership), false));
                    }
                }
            }
            return out;
        });
    }

    /** Writes the state (insert or update). */
    public CompletableFuture<Void> save(TerritoryState s) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(db.dialect().upsert(db.table("territory_state"),
                    List.of("territory_id"), COLUMNS))) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, s.territoryId());
                ps.setString(2, s.state().name());
                ps.setString(3, s.ownerFactionId());
                ps.setLong(4, s.ownerSince());
                ps.setLong(5, s.ownedUntil());
                ps.setLong(6, s.cooldownUntil());
                ps.setString(7, s.capturingFactionId());
                ps.setDouble(8, s.progressSeconds());
                ps.setString(9, s.ownershipId() == null ? null : s.ownershipId().toString());
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes stored state for a removed territory. */
    public CompletableFuture<Void> delete(String territoryId) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("territory_state") + " WHERE territory_id = ?")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, territoryId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Appends a capture-history row. */
    public CompletableFuture<Void> history(String territoryId, String factionId, String event, long at) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("capture_history")
                    + " (territory_id, faction_id, event, occurred_at) VALUES (?, ?, ?, ?)")) {
                ps.setQueryTimeout(db.queryTimeoutSeconds());
                ps.setString(1, territoryId);
                ps.setString(2, factionId);
                ps.setString(3, event);
                ps.setLong(4, at);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
