/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.config.Settings;

/**
 * Decides whether DrugRunTerritories should shield a block from an explosion. Pure logic.
 *
 * <p>This layer only ever removes blocks from an explosion; it never adds destruction that
 * FactionsUUID or WorldGuard would block. Ordinary claim blocks are left entirely to FactionsUUID's
 * own raid rules. DrugRunTerritories adds two things: territory arenas are never damaged, and
 * DrugLab plants and stations follow the land's raid state (so a protected farm cannot be
 * "harvested" with TNT).
 */
public final class RaidPolicy {

    /** Outcome for one block. */
    public enum Decision {
        /** Leave the block in the explosion; other plugins still decide. */
        PASS,
        /** Remove the block from the explosion. */
        PROTECT
    }

    private RaidPolicy() {
    }

    /**
     * @param inArena the block is inside a territory with ARENA protection while arena protection is on
     * @param drugLabBlock the block belongs to a DrugLab plant (or its soil) or station
     * @param landState raid state of the land that owns the DrugLab object (its base block), or
     *                  {@code null} when it could not be determined
     */
    public static Decision decide(boolean inArena, boolean drugLabBlock, RaidState landState, Settings.Raiding cfg) {
        if (inArena) {
            return Decision.PROTECT;
        }
        if (!drugLabBlock) {
            return Decision.PASS;
        }
        if (landState == null) {
            return cfg.failClosed() ? Decision.PROTECT : Decision.PASS;
        }
        return switch (landState) {
            case VULNERABLE -> Decision.PASS;
            case WILDERNESS -> cfg.wildernessDrugLab() == Settings.WildernessExplosions.PREVENT ? Decision.PROTECT : Decision.PASS;
            case SERVER_PROTECTED -> Decision.PROTECT;
            case PROTECTED, SHIELDED, GRACE -> cfg.protectDrugLabInProtectedClaims() ? Decision.PROTECT : Decision.PASS;
        };
    }
}
