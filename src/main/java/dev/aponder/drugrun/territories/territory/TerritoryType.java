/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

/**
 * Category of a territory. It is descriptive (leaderboards, menus, placeholders): what a territory
 * actually grants comes from its {@code bonuses} and {@code income} settings, so new kinds of
 * territory need no engine changes.
 */
public enum TerritoryType {
    DOCKS,
    INDUSTRIAL,
    GREENHOUSE,
    DOWNTOWN,
    CUSTOM
}
