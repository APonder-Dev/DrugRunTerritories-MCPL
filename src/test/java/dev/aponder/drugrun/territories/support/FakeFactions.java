/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.support;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRank;
import dev.aponder.drugrun.territories.faction.FactionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** In-memory {@link FactionProvider} for tests. Land is keyed by "world:chunkX:chunkZ". */
public final class FakeFactions implements FactionProvider {

    public static final FactionRef WILDERNESS = new FactionRef("0", "Wilderness", FactionRef.Kind.WILDERNESS);
    public static final FactionRef SAFEZONE = new FactionRef("-1", "SafeZone", FactionRef.Kind.SAFEZONE);
    public static final FactionRef WARZONE = new FactionRef("-2", "WarZone", FactionRef.Kind.WARZONE);

    public boolean available = true;
    public final Map<String, FactionRef> factions = new HashMap<>();
    public final Map<UUID, String> membership = new HashMap<>();
    public final Map<UUID, FactionRank> ranks = new HashMap<>();
    public final Map<String, FactionRef> land = new HashMap<>();
    public final Map<String, RaidState> raidStates = new HashMap<>();
    public final Map<String, FactionRelation> relations = new HashMap<>();
    public final Map<String, UUID> leaders = new HashMap<>();
    public final Map<String, List<Player>> online = new HashMap<>();
    /** player -> land keys where they may build */
    public final Map<UUID, List<String>> buildable = new HashMap<>();

    public FactionRef add(String id, String name) {
        FactionRef ref = new FactionRef(id, name, FactionRef.Kind.NORMAL);
        factions.put(id, ref);
        raidStates.putIfAbsent(id, RaidState.PROTECTED);
        return ref;
    }

    public void join(UUID player, String factionId) {
        membership.put(player, factionId);
        ranks.putIfAbsent(player, FactionRank.MEMBER);
    }

    public void relate(String a, String b, FactionRelation relation) {
        relations.put(a + "|" + b, relation);
        relations.put(b + "|" + a, relation);
    }

    public void claim(String world, int chunkX, int chunkZ, FactionRef owner) {
        land.put(world + ":" + chunkX + ":" + chunkZ, owner);
    }

    public static String key(Location l) {
        return l.getWorld().getName() + ":" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4);
    }

    private void check() {
        if (!available) {
            throw new FactionLookupException("unavailable", null);
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Optional<FactionRef> factionOf(UUID player) {
        check();
        return Optional.ofNullable(membership.get(player)).map(factions::get);
    }

    @Override
    public FactionRef factionAt(Location location) {
        check();
        return land.getOrDefault(key(location), WILDERNESS);
    }

    @Override
    public Optional<FactionRef> byId(String factionId) {
        check();
        return Optional.ofNullable(factions.get(factionId));
    }

    @Override
    public Optional<FactionRef> byTag(String tag) {
        check();
        return factions.values().stream().filter(f -> f.name().equalsIgnoreCase(tag)).findFirst();
    }

    @Override
    public FactionRelation relation(FactionRef first, FactionRef second) {
        check();
        if (first.id().equals(second.id())) {
            return FactionRelation.SAME_FACTION;
        }
        if (!first.normal() || !second.normal()) {
            return FactionRelation.NONE;
        }
        return relations.getOrDefault(first.id() + "|" + second.id(), FactionRelation.NEUTRAL);
    }

    @Override
    public RaidState raidState(FactionRef owner) {
        check();
        return switch (owner.kind()) {
            case WILDERNESS -> RaidState.WILDERNESS;
            case SAFEZONE, WARZONE -> RaidState.SERVER_PROTECTED;
            case NORMAL -> raidStates.getOrDefault(owner.id(), RaidState.PROTECTED);
        };
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        check();
        FactionRef owner = factionAt(location);
        if (owner.kind() == FactionRef.Kind.WILDERNESS) {
            return true;
        }
        if (owner.id().equals(membership.get(player.getUniqueId()))) {
            return true;
        }
        return buildable.getOrDefault(player.getUniqueId(), List.of()).contains(key(location));
    }

    @Override
    public boolean canDestroy(Player player, Location location) {
        return canBuild(player, location);
    }

    @Override
    public boolean canUseContainers(Player player, Location location) {
        return canBuild(player, location);
    }

    @Override
    public Optional<FactionRank> rankOf(UUID player) {
        check();
        return membership.containsKey(player) ? Optional.of(ranks.getOrDefault(player, FactionRank.MEMBER)) : Optional.empty();
    }

    @Override
    public Optional<UUID> leaderOf(FactionRef faction) {
        check();
        return Optional.ofNullable(leaders.get(faction.id()));
    }

    @Override
    public List<Player> onlineMembers(FactionRef faction) {
        check();
        return new ArrayList<>(online.getOrDefault(faction.id(), List.of()));
    }

    @Override
    public int memberCount(FactionRef faction) {
        check();
        return (int) membership.values().stream().filter(faction.id()::equals).count();
    }
}
