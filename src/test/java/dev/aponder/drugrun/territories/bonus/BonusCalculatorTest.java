/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.bonus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BonusCalculatorTest {

    @Test
    @DisplayName("HIGHEST keeps only the strongest bonus")
    void highest() {
        assertEquals(1.2, BonusCalculator.combine(List.of(1.1, 1.2, 1.05), StackingMode.HIGHEST, 2.0), 1e-9);
    }

    @Test
    @DisplayName("ADDITIVE sums the fractions, then caps")
    void additive() {
        assertEquals(1.2, BonusCalculator.combine(List.of(1.1, 1.1), StackingMode.ADDITIVE, 2.0), 1e-9);
        assertEquals(1.25, BonusCalculator.combine(List.of(1.2, 1.2), StackingMode.ADDITIVE, 1.25), 1e-9);
    }

    @Test
    @DisplayName("CAPPED_MULTIPLICATIVE multiplies, never beyond the hard cap")
    void multiplicative() {
        assertEquals(1.21, BonusCalculator.combine(List.of(1.1, 1.1), StackingMode.CAPPED_MULTIPLICATIVE, 2.0), 1e-9);
        assertEquals(1.25, BonusCalculator.combine(List.of(2.0, 2.0, 2.0, 2.0), StackingMode.CAPPED_MULTIPLICATIVE, 1.25), 1e-9);
    }

    @Test
    @DisplayName("Penalties, NaN and infinity are ignored; empty input is 1.0")
    void invalidValues() {
        assertEquals(1.0, BonusCalculator.combine(List.of(), StackingMode.ADDITIVE, 1.25), 1e-9);
        assertEquals(1.1, BonusCalculator.combine(List.of(0.5, Double.NaN, Double.POSITIVE_INFINITY, 1.1),
                StackingMode.CAPPED_MULTIPLICATIVE, 1.25), 1e-9);
        assertEquals(1.0, BonusCalculator.combine(List.of(5.0), StackingMode.HIGHEST, 0.5), 1e-9, "cap below 1 is treated as 1");
    }

    @Test
    @DisplayName("Price bonus preserves the input price and respects the per-transaction cap")
    void price() {
        assertEquals(110.0, BonusCalculator.applyToPrice(100.0, 1.1, 0), 1e-9);
        assertEquals(105.0, BonusCalculator.applyToPrice(100.0, 1.1, 5.0), 1e-9);
        assertEquals(0.0, BonusCalculator.applyToPrice(0.0, 1.1, 0), 1e-9);
        assertEquals(-3.0, BonusCalculator.applyToPrice(-3.0, 1.1, 0), 1e-9, "negative prices are never boosted");
        assertEquals(100.0, BonusCalculator.applyToPrice(100.0, 0.9, 0), 1e-9);
    }
}
