/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Public read-only API. Obtain it with {@code Bukkit.getServicesManager().load(DrugRunTerritoriesApi.class)}
 * while DrugRunTerritories is enabled. All methods must be called on the server main thread.
 */
public interface DrugRunTerritoriesApi {

    /** Summary of one territory. */
    record TerritoryInfo(String id, String displayName, String type, String state, String ownerFactionId,
                         String capturingFactionId, double progress, long ownedUntil) {
    }

    /** @return every enabled territory */
    List<TerritoryInfo> territories();

    /** @return the enabled territory whose region contains {@code location} */
    Optional<TerritoryInfo> territoryAt(Location location);

    /**
     * @param bonusType one of {@code DEALER_SELL}, {@code PROCESSING_SPEED}, {@code CROP_GROWTH}
     * @return the combined territory multiplier for the player's faction (1.0 when none applies)
     */
    double bonusMultiplier(UUID player, String bonusType);

    /** @return the faction's XP, or 0 when unknown */
    long factionXp(String factionId);

    /** @return the faction's reputation, or 0 when unknown */
    long factionReputation(String factionId);

    /** @return the Loot Key faction policy (also registered as its own service) */
    LootKeyFactionPolicy lootKeyPolicy();
}
