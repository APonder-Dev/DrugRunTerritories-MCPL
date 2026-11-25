/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.bonus;

import java.util.Collection;

/** Combines territory bonus multipliers. Pure and deterministic. */
public final class BonusCalculator {

    private BonusCalculator() {
    }

    /**
     * @param multipliers individual multipliers (1.10 = +10%); values below 1 are ignored, since a
     *                    territory bonus never penalises
     * @param mode how to combine them
     * @param hardCap upper limit on the result, at least 1.0
     * @return combined multiplier between 1.0 and {@code hardCap}
     */
    public static double combine(Collection<Double> multipliers, StackingMode mode, double hardCap) {
        double cap = Math.max(1.0, hardCap);
        double result = switch (mode) {
            case HIGHEST -> {
                double best = 1.0;
                for (double m : multipliers) {
                    if (valid(m)) {
                        best = Math.max(best, m);
                    }
                }
                yield best;
            }
            case ADDITIVE -> {
                double sum = 1.0;
                for (double m : multipliers) {
                    if (valid(m)) {
                        sum += m - 1.0;
                    }
                }
                yield sum;
            }
            case CAPPED_MULTIPLICATIVE -> {
                double product = 1.0;
                for (double m : multipliers) {
                    if (valid(m)) {
                        product *= m;
                        if (product >= cap) {
                            break;
                        }
                    }
                }
                yield product;
            }
        };
        return Math.min(result, cap);
    }

    private static boolean valid(double m) {
        return !Double.isNaN(m) && !Double.isInfinite(m) && m > 1.0;
    }

    /**
     * Applies a dealer bonus to a sale price.
     *
     * @param price price DrugLab already computed (quality, demand, daily request and boosters included)
     * @param multiplier combined territory multiplier
     * @param maxBonus largest amount the bonus may add, or 0 for no limit
     * @return the new price, never below {@code price}
     */
    public static double applyToPrice(double price, double multiplier, double maxBonus) {
        if (!(price > 0) || Double.isInfinite(price) || !(multiplier > 1.0)) {
            return price;
        }
        double bonus = price * (multiplier - 1.0);
        if (maxBonus > 0) {
            bonus = Math.min(bonus, maxBonus);
        }
        return price + bonus;
    }
}
