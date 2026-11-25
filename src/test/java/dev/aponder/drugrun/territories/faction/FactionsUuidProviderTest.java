/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.FactionsPlugin;
import dev.kitteh.factions.Universe;
import dev.kitteh.factions.permissible.PermissibleActions;
import dev.kitteh.factions.permissible.Relation;
import dev.kitteh.factions.permissible.Role;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** FactionsUUID adapter against mocked FactionsUUID 4.6.0 interfaces (spec tests 1-10). */
class FactionsUuidProviderTest {

    private final FPlayers players = mock(FPlayers.class);
    private final Factions factions = mock(Factions.class);
    private final Board board = mock(Board.class);
    private final Universe universe = mock(Universe.class);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final FactionsUuidProvider provider = new FactionsUuidProvider(enabled::get, () -> players, () -> factions, () -> board,
            () -> universe);

    private final UUID alice = UUID.randomUUID();
    private final Faction alpha = faction(10, "Alpha");
    private final Faction bravo = faction(20, "Bravo");
    private final Faction wilderness = mock(Faction.class);
    private final Faction safezone = mock(Faction.class);
    private World world;

    private static Faction faction(int id, String tag) {
        Faction f = mock(Faction.class);
        when(f.id()).thenReturn(id);
        when(f.tag()).thenReturn(tag);
        when(f.isNormal()).thenReturn(true);
        return f;
    }

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(wilderness.isWilderness()).thenReturn(true);
        when(wilderness.id()).thenReturn(0);
        when(wilderness.tag()).thenReturn("Wilderness");
        when(safezone.isSafeZone()).thenReturn(true);
        when(safezone.id()).thenReturn(-1);
        when(safezone.tag()).thenReturn("SafeZone");
        when(factions.get(10)).thenReturn(alpha);
        when(factions.get(20)).thenReturn(bravo);
        when(board.factionAt(any())).thenReturn(wilderness);
        FPlayer fp = mock(FPlayer.class);
        when(players.has(alice)).thenReturn(true);
        when(players.get(alice)).thenReturn(fp);
        when(fp.hasFaction()).thenReturn(true);
        when(fp.faction()).thenReturn(alpha);
        when(fp.role()).thenReturn(Role.MODERATOR);
    }

    private Location at(int x, int z) {
        return new Location(world, x, 64, z);
    }

    @Test
    @DisplayName("1: faction membership and rank")
    void membership() {
        assertEquals("10", provider.factionOf(alice).orElseThrow().id());
        assertEquals(FactionRank.MODERATOR, provider.rankOf(alice).orElseThrow());
        UUID stranger = UUID.randomUUID();
        assertTrue(provider.factionOf(stranger).isEmpty());
    }

    @Test
    @DisplayName("2/5: ownership lookup by chunk, including wilderness and safezone")
    void ownership() {
        when(board.factionAt(new FLocation("world", 1, 2))).thenReturn(alpha);
        when(board.factionAt(new FLocation("world", 5, 5))).thenReturn(safezone);
        assertEquals("Alpha", provider.factionAt(at(16 + 3, 32 + 3)).name());
        assertEquals(FactionRef.Kind.WILDERNESS, provider.factionAt(at(1000, 1000)).kind());
        assertEquals(FactionRef.Kind.SAFEZONE, provider.factionAt(at(80, 80)).kind());
        assertEquals(RaidState.WILDERNESS, provider.raidStateAt(at(1000, 1000)));
        assertEquals(RaidState.SERVER_PROTECTED, provider.raidStateAt(at(80, 80)));
    }

    @Test
    @DisplayName("3/4: relations map from FactionsUUID's Relation enum")
    void relations() {
        when(alpha.relationTo(bravo)).thenReturn(Relation.ALLY);
        FactionRef a = provider.byId("10").orElseThrow();
        FactionRef b = provider.byId("20").orElseThrow();
        assertEquals(FactionRelation.ALLY, provider.relation(a, b));
        when(alpha.relationTo(bravo)).thenReturn(Relation.ENEMY);
        assertEquals(FactionRelation.ENEMY, provider.relation(a, b));
        when(alpha.relationTo(bravo)).thenReturn(Relation.TRUCE);
        assertEquals(FactionRelation.TRUCE, provider.relation(a, b));
        assertEquals(FactionRelation.SAME_FACTION, provider.relation(a, a));
    }

    @Test
    @DisplayName("6: disbanded faction ids resolve to nothing")
    void disbanded() {
        assertTrue(provider.byId("999").isEmpty());
        assertTrue(provider.byId("not-a-number").isEmpty());
        assertEquals(RaidState.WILDERNESS, provider.raidState(new FactionRef("999", "Gone", FactionRef.Kind.NORMAL)));
    }

    @Test
    @DisplayName("8/9/10: DTR raidability, shields and grace map to raid states with the documented precedence")
    void raidStates() {
        FactionRef a = new FactionRef("10", "Alpha", FactionRef.Kind.NORMAL);
        assertEquals(RaidState.PROTECTED, provider.raidState(a));
        when(alpha.isRaidable()).thenReturn(true);
        assertEquals(RaidState.VULNERABLE, provider.raidState(a));
        when(alpha.shieldActive()).thenReturn(true);
        assertEquals(RaidState.SHIELDED, provider.raidState(a), "a shield beats DTR 0");
        when(universe.grace()).thenReturn(true);
        assertEquals(RaidState.GRACE, provider.raidState(a), "server grace beats everything for claims");
        when(universe.grace()).thenReturn(false);
        when(alpha.shieldActive()).thenReturn(false);
        when(alpha.isPeaceful()).thenReturn(true);
        assertEquals(RaidState.PROTECTED, provider.raidState(a), "peaceful factions are never vulnerable");
    }

    @Test
    @DisplayName("Build access delegates to Faction#hasAccess; safezones deny; wilderness allows")
    void access() {
        // PermissibleActions' static initialiser reads FactionsUUID's translations from the running plugin.
        try (MockedStatic<FactionsPlugin> plugin = mockStatic(FactionsPlugin.class)) {
            plugin.when(FactionsPlugin::instance).thenReturn(mock(FactionsPlugin.class, RETURNS_DEEP_STUBS));
            accessChecks();
        }
    }

    private void accessChecks() {
        PermissibleActions build = PermissibleActions.BUILD; // initialise the enum before any matcher is recorded
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(alice);
        FLocation claimed = new FLocation("world", 0, 0);
        when(board.factionAt(claimed)).thenReturn(bravo);
        when(bravo.hasAccess(any(FPlayer.class), eq(build), eq(claimed))).thenReturn(false);
        assertFalse(provider.canBuild(player, at(3, 3)));
        when(bravo.hasAccess(any(FPlayer.class), eq(build), eq(claimed))).thenReturn(true);
        assertTrue(provider.canBuild(player, at(3, 3)));
        assertTrue(provider.canBuild(player, at(500, 500)));
        when(board.factionAt(claimed)).thenReturn(safezone);
        assertFalse(provider.canBuild(player, at(3, 3)));
    }

    @Test
    @DisplayName("70: a disabled or failing FactionsUUID raises FactionLookupException (callers fail closed)")
    void unavailable() {
        enabled.set(false);
        assertThrows(FactionLookupException.class, () -> provider.factionOf(alice));
        enabled.set(true);
        when(board.factionAt(any())).thenThrow(new IllegalStateException("boom"));
        assertThrows(FactionLookupException.class, () -> provider.factionAt(at(0, 0)));
    }
}
