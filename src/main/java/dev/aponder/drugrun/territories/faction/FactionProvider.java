/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.RaidState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Read-only view of the faction plugin. The faction plugin stays authoritative: nothing here
 * changes faction data, claims, DTR or shields.
 *
 * <p>All methods must be called on the server main thread. Any method may throw
 * {@link FactionLookupException} when the faction plugin cannot answer; callers must then fail
 * closed (deny the protected action, withhold the reward).
 */
public interface FactionProvider {

    /** @return whether the faction plugin is loaded and enabled */
    boolean available();

    /** @return the player's faction, or empty when the player has none */
    Optional<FactionRef> factionOf(UUID player);

    /** @return the land owner at {@code location}; wilderness when unclaimed */
    FactionRef factionAt(Location location);

    /** @return the faction with this id, or empty when it no longer exists */
    Optional<FactionRef> byId(String factionId);

    /** @return the player faction with this tag (case-insensitive), or empty */
    Optional<FactionRef> byTag(String tag);

    /** @return relation between two factions; {@link FactionRelation#SAME_FACTION} when equal */
    FactionRelation relation(FactionRef first, FactionRef second);

    /** @return relation between two players' factions; {@link FactionRelation#NONE} when either has none */
    default FactionRelation relation(UUID first, UUID second) {
        Optional<FactionRef> a = factionOf(first);
        Optional<FactionRef> b = factionOf(second);
        if (a.isEmpty() || b.isEmpty()) {
            return FactionRelation.NONE;
        }
        return relation(a.get(), b.get());
    }

    /** @return raid state of the land owned by {@code owner} */
    RaidState raidState(FactionRef owner);

    /** @return raid state of the land at {@code location} */
    default RaidState raidStateAt(Location location) {
        return raidState(factionAt(location));
    }

    /** @return whether the faction plugin lets {@code player} build at {@code location} */
    boolean canBuild(Player player, Location location);

    /** @return whether the faction plugin lets {@code player} break blocks at {@code location} */
    boolean canDestroy(Player player, Location location);

    /** @return whether the faction plugin lets {@code player} open containers at {@code location} */
    boolean canUseContainers(Player player, Location location);

    /** @return the player's rank, or empty when the player has no faction */
    Optional<FactionRank> rankOf(UUID player);

    /** @return the faction leader's UUID, or empty when the faction has none or does not exist */
    Optional<UUID> leaderOf(FactionRef faction);

    /** @return members of {@code faction} who are online and visible */
    List<Player> onlineMembers(FactionRef faction);

    /** @return number of members, or 0 when the faction does not exist */
    int memberCount(FactionRef faction);
}
