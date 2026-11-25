/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.lootkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.KillDecision;
import dev.aponder.drugrun.territories.api.KillDecision.Outcome;
import dev.aponder.drugrun.territories.api.KillDecision.Reason;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec tests 51-60 (faction policy side; key lifecycle stays in DrugRunCore). */
class FactionKillPolicyTest {

    private final UUID killer = UUID.randomUUID();
    private final UUID victim = UUID.randomUUID();
    private final FakeFactions factions = new FakeFactions();
    private World world;
    private KillPolicySettings settings;
    private TerritoryRegistry registry;
    private FactionKillPolicy policy;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        factions.add("1", "Alpha");
        factions.add("2", "Bravo");
        factions.join(killer, "1");
        factions.join(victim, "2");
        settings = Fixtures.settings().killPolicy();
        registry = new TerritoryRegistry(List.of(Fixtures.territory("docks", 1000, 1000, Map.of())));
        policy = new FactionKillPolicy(factions, () -> settings, () -> registry);
    }

    private Location at(int x, int z) {
        return new Location(world, x, 64, z);
    }

    @Test
    @DisplayName("51: enemy faction kill is eligible")
    void enemy() {
        factions.relate("1", "2", FactionRelation.ENEMY);
        factions.claim("world", 0, 0, factions.factions.get("2"));
        KillDecision d = policy.evaluate(killer, victim, at(5, 5));
        assertEquals(Outcome.ALLOW, d.outcome());
        assertEquals(Reason.ENEMY_FACTION, d.reason());
    }

    @Test
    @DisplayName("52: same-faction kill is denied")
    void sameFaction() {
        factions.join(victim, "1");
        assertEquals(Reason.SAME_FACTION, policy.evaluate(killer, victim, at(5, 5)).reason());
        assertFalse(policy.evaluate(killer, victim, at(5, 5)).allowed());
    }

    @Test
    @DisplayName("53: allied and truce kills are denied")
    void allied() {
        factions.relate("1", "2", FactionRelation.ALLY);
        assertEquals(Reason.ALLIED_FACTION, policy.evaluate(killer, victim, at(5, 5)).reason());
        factions.relate("1", "2", FactionRelation.TRUCE);
        assertEquals(Reason.ALLIED_FACTION, policy.evaluate(killer, victim, at(5, 5)).reason());
    }

    @Test
    @DisplayName("54: safezone kills are denied")
    void safezone() {
        factions.claim("world", 0, 0, FakeFactions.SAFEZONE);
        assertEquals(Reason.SAFEZONE, policy.evaluate(killer, victim, at(5, 5)).reason());
    }

    @Test
    @DisplayName("55: wilderness PvP between neutral factions is eligible")
    void wilderness() {
        KillDecision d = policy.evaluate(killer, victim, at(5, 5));
        assertEquals(Outcome.ALLOW, d.outcome());
        assertEquals(Reason.WILDERNESS, d.reason());
    }

    @Test
    @DisplayName("56: faction plugin unavailable gives UNAVAILABLE, which is not allowed")
    void unavailable() {
        factions.available = false;
        KillDecision d = policy.evaluate(killer, victim, at(5, 5));
        assertEquals(Outcome.UNAVAILABLE, d.outcome());
        assertFalse(d.allowed());
        assertEquals(RaidState.SERVER_PROTECTED, policy.raidStateAt(at(5, 5)), "raid state fails closed too");
    }

    @Test
    @DisplayName("Self-kills are denied")
    void self() {
        assertEquals(Reason.SELF_KILL, policy.evaluate(killer, killer, at(5, 5)).reason());
    }

    @Test
    @DisplayName("Territory overrides apply but never re-enable same-faction or allied kills")
    void overrides() {
        settings = new KillPolicySettings(true, true, true, true, true, true, true, true, Map.of("docks", Outcome.DENY));
        assertEquals(Reason.TERRITORY_OVERRIDE, policy.evaluate(killer, victim, at(1010, 1010)).reason());
        assertEquals(Reason.WILDERNESS, policy.evaluate(killer, victim, at(5, 5)).reason());
        settings = new KillPolicySettings(true, true, true, true, true, true, true, true, Map.of("docks", Outcome.ALLOW));
        factions.relate("1", "2", FactionRelation.ALLY);
        assertEquals(Reason.ALLIED_FACTION, policy.evaluate(killer, victim, at(1010, 1010)).reason());
    }

    @Test
    @DisplayName("Factionless victim in a claimed chunk follows allow-neutral-kills; warzone follows allow-warzone-pvp")
    void neutralAndWarzone() {
        UUID loner = UUID.randomUUID();
        factions.claim("world", 0, 0, factions.factions.get("2"));
        assertEquals(Reason.NEUTRAL, policy.evaluate(killer, loner, at(5, 5)).reason());
        factions.claim("world", 0, 0, FakeFactions.WARZONE);
        assertEquals(Reason.WARZONE, policy.evaluate(killer, loner, at(5, 5)).reason());
        settings = new KillPolicySettings(true, true, true, true, true, true, false, true, Map.of());
        assertEquals(Outcome.DENY, policy.evaluate(killer, loner, at(5, 5)).outcome());
    }
}
