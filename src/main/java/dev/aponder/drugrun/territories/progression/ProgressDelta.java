/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

/** A change to a faction's progression. All fields are non-negative. */
public record ProgressDelta(long xp, long reputation, int captures, int defenses, int raidVictories, long controlSeconds,
                            long harvests, double income, long itemsSold, double salesValue, long jobsProcessed) {

    public ProgressDelta {
        if (xp < 0 || reputation < 0 || captures < 0 || defenses < 0 || raidVictories < 0 || controlSeconds < 0
                || harvests < 0 || income < 0 || Double.isNaN(income) || itemsSold < 0 || salesValue < 0 || Double.isNaN(salesValue)
                || jobsProcessed < 0) {
            throw new IllegalArgumentException("Progress deltas cannot be negative");
        }
    }

    /** Delta with only the original eight fields. */
    public ProgressDelta(long xp, long reputation, int captures, int defenses, int raidVictories, long controlSeconds, long harvests,
                         double income) {
        this(xp, reputation, captures, defenses, raidVictories, controlSeconds, harvests, income, 0, 0, 0);
    }

    public static ProgressDelta sale(long items, double value, long xp) {
        return new ProgressDelta(xp, 0, 0, 0, 0, 0, 0, 0, items, value, 0);
    }

    public static ProgressDelta processed(long xp) {
        return new ProgressDelta(xp, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
    }

    public static ProgressDelta xp(long xp, long reputation) {
        return new ProgressDelta(xp, reputation, 0, 0, 0, 0, 0, 0);
    }

    public static ProgressDelta capture(long xp, long reputation) {
        return new ProgressDelta(xp, reputation, 1, 0, 0, 0, 0, 0);
    }

    public static ProgressDelta defense(long xp, long reputation) {
        return new ProgressDelta(xp, reputation, 0, 1, 0, 0, 0, 0);
    }

    public static ProgressDelta raidVictory(long xp, long reputation) {
        return new ProgressDelta(xp, reputation, 0, 0, 1, 0, 0, 0);
    }

    public static ProgressDelta control(long seconds, long xp) {
        return new ProgressDelta(xp, 0, 0, 0, 0, seconds, 0, 0);
    }

    public static ProgressDelta harvest(long xp) {
        return new ProgressDelta(xp, 0, 0, 0, 0, 0, 1, 0);
    }

    public static ProgressDelta income(double amount) {
        return new ProgressDelta(0, 0, 0, 0, 0, 0, 0, amount);
    }

    /** @return the sum of this and {@code o} */
    public ProgressDelta plus(ProgressDelta o) {
        return new ProgressDelta(xp + o.xp, reputation + o.reputation, captures + o.captures, defenses + o.defenses,
                raidVictories + o.raidVictories, controlSeconds + o.controlSeconds, harvests + o.harvests, income + o.income,
                itemsSold + o.itemsSold, salesValue + o.salesValue, jobsProcessed + o.jobsProcessed);
    }

    public boolean empty() {
        return xp == 0 && reputation == 0 && captures == 0 && defenses == 0 && raidVictories == 0 && controlSeconds == 0
                && harvests == 0 && income == 0 && itemsSold == 0 && salesValue == 0 && jobsProcessed == 0;
    }
}
