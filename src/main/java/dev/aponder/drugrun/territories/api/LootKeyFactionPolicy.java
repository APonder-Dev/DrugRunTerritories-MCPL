/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

import java.util.UUID;
import org.bukkit.Location;

/**
 * Faction and territory rules for DrugRunCore's PvP Loot Keys.
 *
 * <p>DrugRunTerritories registers an implementation with Bukkit's {@code ServicesManager} while it
 * is enabled. DrugRunCore (or any other plugin) looks it up with
 * {@code Bukkit.getServicesManager().load(LootKeyFactionPolicy.class)}. This plugin only answers
 * policy questions: key generation, storage, redemption, remaining uses, expiry and physical
 * disintegration all stay in DrugRunCore.
 *
 * <p>Threading: call from the server main thread. Fail-closed: when the service is missing or
 * returns {@link KillDecision.Outcome#UNAVAILABLE}, callers should not generate a key.
 */
public interface LootKeyFactionPolicy {

    /**
     * @param killer UUID of the player credited with the kill
     * @param victim UUID of the player who died
     * @param deathLocation where the victim died
     * @return the decision, never {@code null}
     */
    KillDecision evaluate(UUID killer, UUID victim, Location deathLocation);

    /**
     * @return relation between two players' factions, or {@link FactionRelation#NONE} when either has none
     */
    FactionRelation relation(UUID first, UUID second);

    /** @return raid state of the land at {@code location} */
    RaidState raidStateAt(Location location);

    /** @return whether a FactionsUUID shield currently covers the land at {@code location} */
    default boolean shieldedAt(Location location) {
        return raidStateAt(location) == RaidState.SHIELDED;
    }
}
