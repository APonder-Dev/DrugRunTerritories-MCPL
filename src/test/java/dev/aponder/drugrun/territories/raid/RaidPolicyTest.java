/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import static dev.aponder.drugrun.territories.raid.RaidPolicy.Decision.PASS;
import static dev.aponder.drugrun.territories.raid.RaidPolicy.Decision.PROTECT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.config.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec tests 26-35 at the policy level. */
class RaidPolicyTest {

    private static final Settings.Raiding DEFAULT = new Settings.Raiding(true, Settings.WildernessExplosions.ALLOW, true,
            Settings.ExplosionDrops.NORMAL, 0.5);

    @Test
    @DisplayName("Territory arenas are always protected")
    void arena() {
        for (RaidState s : RaidState.values()) {
            assertEquals(PROTECT, RaidPolicy.decide(true, false, s, DEFAULT));
            assertEquals(PROTECT, RaidPolicy.decide(true, true, s, DEFAULT));
        }
    }

    @Test
    @DisplayName("Ordinary claim blocks are left to FactionsUUID in every state")
    void ordinaryBlocks() {
        for (RaidState s : RaidState.values()) {
            assertEquals(PASS, RaidPolicy.decide(false, false, s, DEFAULT));
        }
        assertEquals(PASS, RaidPolicy.decide(false, false, null, DEFAULT));
    }

    @Test
    @DisplayName("DrugLab crops and stations: protected, shielded and grace claims are safe; raidable claims are not")
    void drugLab() {
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.PROTECTED, DEFAULT));
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.SHIELDED, DEFAULT));
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.GRACE, DEFAULT));
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.SERVER_PROTECTED, DEFAULT));
        assertEquals(PASS, RaidPolicy.decide(false, true, RaidState.VULNERABLE, DEFAULT));
        assertEquals(PASS, RaidPolicy.decide(false, true, RaidState.WILDERNESS, DEFAULT));
    }

    @Test
    @DisplayName("Wilderness PREVENT and disabled claim protection")
    void switches() {
        Settings.Raiding prevent = new Settings.Raiding(false, Settings.WildernessExplosions.PREVENT, true, Settings.ExplosionDrops.NONE, 0.5);
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.WILDERNESS, prevent));
        assertEquals(PASS, RaidPolicy.decide(false, true, RaidState.PROTECTED, prevent));
        assertEquals(PROTECT, RaidPolicy.decide(false, true, RaidState.SERVER_PROTECTED, prevent));
    }

    @Test
    @DisplayName("Unknown raid state fails closed by default")
    void failClosed() {
        assertEquals(PROTECT, RaidPolicy.decide(false, true, null, DEFAULT));
        Settings.Raiding open = new Settings.Raiding(true, Settings.WildernessExplosions.ALLOW, false, Settings.ExplosionDrops.NORMAL, 0.5);
        assertEquals(PASS, RaidPolicy.decide(false, true, null, open));
    }
}
