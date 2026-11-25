/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

/** Faction rank, lowest first. Mapped from the faction plugin's own roles. */
public enum FactionRank {
    RECRUIT,
    MEMBER,
    MODERATOR,
    COLEADER,
    LEADER;

    /** @return whether this rank is at least {@code other} */
    public boolean atLeast(FactionRank other) {
        return ordinal() >= other.ordinal();
    }
}
