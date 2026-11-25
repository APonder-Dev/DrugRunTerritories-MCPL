/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.bonus;

/**
 * Kinds of bonus a territory can grant its controlling faction. Adding a type here and handling
 * it in a consumer is all a new territory kind needs; the capture engine does not change.
 */
public enum BonusType {
    /** Multiplies DrugLab dealer sale prices. Applied through {@code ShopTransactionEvent}. */
    DEALER_SELL,
    /** Multiplies DrugLab processing speed through DrugLab's {@code ModifierProvider} hook. */
    PROCESSING_SPEED,
    /** Multiplies DrugLab crop growth speed through DrugLab's {@code ModifierProvider} hook. */
    CROP_GROWTH
}
