/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.bonus;

/** How several territory bonuses of the same type combine for one faction. */
public enum StackingMode {
    /** Only the strongest bonus applies. */
    HIGHEST,
    /** Bonus fractions add up: 1.10 and 1.10 give 1.20. Then the hard cap applies. */
    ADDITIVE,
    /** Bonuses multiply: 1.10 and 1.10 give 1.21. Then the hard cap applies. */
    CAPPED_MULTIPLICATIVE
}
