/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.config.Settings;
import java.time.Duration;

/** Effective capture rules for one territory (global defaults with its overrides applied). */
public record CaptureRules(
        Duration duration,
        int minimumPlayers,
        Settings.ContestedBehavior contestedBehavior,
        double decayPerSecond,
        boolean decayWhenAbandoned,
        Duration ownership,
        Duration cooldown,
        boolean allowChallenges) {

    /** @return capture duration in seconds, as progress is measured */
    public double durationSeconds() {
        return duration.toMillis() / 1000.0;
    }
}
