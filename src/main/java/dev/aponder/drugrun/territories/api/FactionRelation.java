/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

/** Relationship between two players' factions, independent of the faction plugin in use. */
public enum FactionRelation {
    /** Both players belong to the same faction. */
    SAME_FACTION,
    ALLY,
    TRUCE,
    NEUTRAL,
    ENEMY,
    /** At least one player has no faction. */
    NONE
}
