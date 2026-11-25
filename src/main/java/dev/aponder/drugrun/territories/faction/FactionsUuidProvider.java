/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.Universe;
import dev.kitteh.factions.permissible.PermissibleAction;
import dev.kitteh.factions.permissible.PermissibleActions;
import dev.kitteh.factions.permissible.Relation;
import dev.kitteh.factions.permissible.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@link FactionProvider} backed by FactionsUUID 4.x ({@code dev.kitteh.factions}).
 *
 * <p>Every call reads FactionsUUID's own in-memory data; DrugRunTerritories keeps no copy of
 * faction membership or claims, so there is nothing to fall out of sync.
 *
 * <p>Verified against FactionsUUID 4.6.0: {@code FPlayers.fPlayers()}, {@code Factions.factions()},
 * {@code Board.board()}, {@code Universe.universe()}, {@code Faction#isRaidable()},
 * {@code Faction#shieldActive()}, {@code Faction#hasAccess(Selectable, PermissibleAction, FLocation)}.
 */
public final class FactionsUuidProvider implements FactionProvider {

    private final BooleanSupplier enabled;
    private final Supplier<FPlayers> players;
    private final Supplier<Factions> factions;
    private final Supplier<Board> board;
    private final Supplier<Universe> universe;

    /** Production constructor using FactionsUUID's static accessors. */
    public FactionsUuidProvider(BooleanSupplier enabled) {
        this(enabled, FPlayers::fPlayers, Factions::factions, Board::board, Universe::universe);
    }

    /** Constructor with injectable accessors, for tests. */
    public FactionsUuidProvider(BooleanSupplier enabled, Supplier<FPlayers> players, Supplier<Factions> factions,
                                Supplier<Board> board, Supplier<Universe> universe) {
        this.enabled = enabled;
        this.players = players;
        this.factions = factions;
        this.board = board;
        this.universe = universe;
    }

    @Override
    public boolean available() {
        return enabled.getAsBoolean();
    }

    @Override
    public Optional<FactionRef> factionOf(UUID player) {
        return guard(() -> {
            FPlayers all = players.get();
            if (!all.has(player)) {
                return Optional.<FactionRef>empty();
            }
            FPlayer fp = all.get(player);
            if (!fp.hasFaction()) {
                return Optional.<FactionRef>empty();
            }
            Faction faction = fp.faction();
            return faction != null && faction.isNormal() ? Optional.of(ref(faction)) : Optional.<FactionRef>empty();
        });
    }

    @Override
    public FactionRef factionAt(Location location) {
        return guard(() -> ref(board.get().factionAt(flocation(location))));
    }

    @Override
    public Optional<FactionRef> byId(String factionId) {
        return guard(() -> Optional.ofNullable(find(factionId)).map(FactionsUuidProvider::ref));
    }

    @Override
    public Optional<FactionRef> byTag(String tag) {
        return guard(() -> Optional.ofNullable(factions.get().get(tag)).filter(Faction::isNormal).map(FactionsUuidProvider::ref));
    }

    @Override
    public FactionRelation relation(FactionRef first, FactionRef second) {
        if (first.id().equals(second.id())) {
            return FactionRelation.SAME_FACTION;
        }
        if (!first.normal() || !second.normal()) {
            return FactionRelation.NONE;
        }
        return guard(() -> {
            Faction a = find(first.id());
            Faction b = find(second.id());
            if (a == null || b == null) {
                return FactionRelation.NONE;
            }
            return map(a.relationTo(b));
        });
    }

    @Override
    public RaidState raidState(FactionRef owner) {
        return switch (owner.kind()) {
            case WILDERNESS -> RaidState.WILDERNESS;
            case SAFEZONE, WARZONE -> RaidState.SERVER_PROTECTED;
            case NORMAL -> guard(() -> {
                Faction faction = find(owner.id());
                if (faction == null) {
                    // Disbanded between the lookup and now: the land is unclaimed.
                    return RaidState.WILDERNESS;
                }
                if (universe.get().grace()) {
                    return RaidState.GRACE;
                }
                if (faction.shieldActive()) {
                    return RaidState.SHIELDED;
                }
                if (faction.isPeaceful()) {
                    return RaidState.PROTECTED;
                }
                return faction.isRaidable() ? RaidState.VULNERABLE : RaidState.PROTECTED;
            });
        };
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        return access(player, location, PermissibleActions.BUILD);
    }

    @Override
    public boolean canDestroy(Player player, Location location) {
        return access(player, location, PermissibleActions.DESTROY);
    }

    @Override
    public boolean canUseContainers(Player player, Location location) {
        return access(player, location, PermissibleActions.CONTAINER);
    }

    private boolean access(Player player, Location location, PermissibleAction action) {
        return guard(() -> {
            FLocation flocation = flocation(location);
            Faction owner = board.get().factionAt(flocation);
            FPlayer fp = players.get().get(player.getUniqueId());
            if (fp.adminBypass()) {
                return true;
            }
            if (owner == null || owner.isWilderness()) {
                return true;
            }
            if (owner.isSafeZone() || owner.isWarZone()) {
                return false;
            }
            return owner.hasAccess(fp, action, flocation);
        });
    }

    @Override
    public Optional<FactionRank> rankOf(UUID player) {
        return guard(() -> {
            FPlayers all = players.get();
            if (!all.has(player)) {
                return Optional.<FactionRank>empty();
            }
            FPlayer fp = all.get(player);
            if (!fp.hasFaction() || fp.role() == null) {
                return Optional.<FactionRank>empty();
            }
            return Optional.of(map(fp.role()));
        });
    }

    @Override
    public Optional<UUID> leaderOf(FactionRef faction) {
        return guard(() -> {
            Faction f = find(faction.id());
            if (f == null || f.admin() == null) {
                return Optional.<UUID>empty();
            }
            return Optional.of(f.admin().uniqueId());
        });
    }

    @Override
    public List<Player> onlineMembers(FactionRef faction) {
        return guard(() -> {
            Faction f = find(faction.id());
            return f == null ? List.<Player>of() : List.copyOf(f.membersOnlineAsPlayers());
        });
    }

    @Override
    public int memberCount(FactionRef faction) {
        return guard(() -> {
            Faction f = find(faction.id());
            return f == null ? 0 : f.size();
        });
    }

    // ---- mapping -------------------------------------------------------------------------

    private Faction find(String id) {
        int numeric;
        try {
            numeric = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return null;
        }
        return factions.get().get(numeric);
    }

    static FactionRef ref(Faction faction) {
        FactionRef.Kind kind;
        if (faction == null || faction.isWilderness()) {
            kind = FactionRef.Kind.WILDERNESS;
        } else if (faction.isSafeZone()) {
            kind = FactionRef.Kind.SAFEZONE;
        } else if (faction.isWarZone()) {
            kind = FactionRef.Kind.WARZONE;
        } else {
            kind = FactionRef.Kind.NORMAL;
        }
        if (faction == null) {
            return new FactionRef(String.valueOf(Factions.ID_WILDERNESS), "Wilderness", kind);
        }
        return new FactionRef(String.valueOf(faction.id()), faction.tag(), kind);
    }

    static FactionRelation map(Relation relation) {
        if (relation == null) {
            return FactionRelation.NONE;
        }
        if (relation.isMember()) {
            return FactionRelation.SAME_FACTION;
        }
        if (relation.isAlly()) {
            return FactionRelation.ALLY;
        }
        if (relation.isTruce()) {
            return FactionRelation.TRUCE;
        }
        if (relation.isEnemy()) {
            return FactionRelation.ENEMY;
        }
        return FactionRelation.NEUTRAL;
    }

    static FactionRank map(Role role) {
        return switch (role.name()) {
            case "ADMIN" -> FactionRank.LEADER;
            case "COLEADER" -> FactionRank.COLEADER;
            case "MODERATOR" -> FactionRank.MODERATOR;
            case "NORMAL" -> FactionRank.MEMBER;
            default -> FactionRank.RECRUIT;
        };
    }

    private static FLocation flocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new FactionLookupException("Location has no world", null);
        }
        return new FLocation(world.getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private <T> T guard(Supplier<T> call) {
        if (!available()) {
            throw new FactionLookupException("FactionsUUID is not enabled", null);
        }
        try {
            return call.get();
        } catch (FactionLookupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FactionLookupException("FactionsUUID lookup failed: " + e.getMessage(), e);
        }
    }
}
