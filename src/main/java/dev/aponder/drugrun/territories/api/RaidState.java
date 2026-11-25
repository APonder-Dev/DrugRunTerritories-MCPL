/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

/**
 * Stable summary of how open a piece of land is to raiding. This is read from FactionsUUID,
 * which stays authoritative; DrugRunTerritories never changes a faction's raid state.
 *
 * <p>Precedence when several apply: {@code SERVER_PROTECTED}, {@code WILDERNESS}, {@code GRACE},
 * {@code SHIELDED}, {@code VULNERABLE}, {@code PROTECTED}.
 */
public enum RaidState {
    /** A normal faction claim that is not raidable right now. */
    PROTECTED,
    /** A normal faction claim whose faction is raidable (FactionsUUID's land-raid control says so). */
    VULNERABLE,
    /** A faction claim covered by an active FactionsUUID shield. */
    SHIELDED,
    /** FactionsUUID's server-wide grace period is running. */
    GRACE,
    /** Unclaimed land. */
    WILDERNESS,
    /** Safezone, warzone, or another server-owned area. */
    SERVER_PROTECTED;

    /** @return whether claim blocks here may be destroyed by raiders */
    public boolean raidable() {
        return this == VULNERABLE;
    }
}
