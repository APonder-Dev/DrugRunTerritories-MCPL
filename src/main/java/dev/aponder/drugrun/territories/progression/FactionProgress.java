/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

/** Accumulated progression and statistics for one faction. Immutable. */
public record FactionProgress(
        String factionId,
        long xp,
        long reputation,
        int captures,
        int defenses,
        int raidVictories,
        long controlSeconds,
        long harvests,
        double incomeEarned,
        long itemsSold,
        double salesValue,
        long jobsProcessed) {

    public static FactionProgress empty(String factionId) {
        return new FactionProgress(factionId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** @return this progress plus {@code d} */
    public FactionProgress plus(ProgressDelta d) {
        return new FactionProgress(factionId, xp + d.xp(), reputation + d.reputation(), captures + d.captures(),
                defenses + d.defenses(), raidVictories + d.raidVictories(), controlSeconds + d.controlSeconds(),
                harvests + d.harvests(), incomeEarned + d.income(), itemsSold + d.itemsSold(), salesValue + d.salesValue(),
                jobsProcessed + d.jobsProcessed());
    }
}
