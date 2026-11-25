/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Capture lifecycle of a territory.
 *
 * <p>Legal gameplay transitions:
 * <pre>
 * AVAILABLE  -> CAPTURING                         a faction meets the requirements alone in the zone
 * CAPTURING  -> CONTESTED                         another faction's eligible player enters
 * CAPTURING  -> CONTROLLED                        progress completes, or a challenge fails (owner keeps it)
 * CAPTURING  -> AVAILABLE                         the capture fails with no previous owner
 * CONTESTED  -> CAPTURING                         the other factions leave
 * CONTESTED  -> CONTROLLED                        a challenge fails (owner keeps it)
 * CONTESTED  -> AVAILABLE                         the capture fails with no previous owner
 * CONTROLLED -> CAPTURING                         a challenger starts capturing after the cooldown
 * CONTROLLED -> EXPIRED                           ownership time runs out
 * EXPIRED    -> COOLDOWN | AVAILABLE              immediately after expiry (AVAILABLE when cooldown is 0)
 * COOLDOWN   -> AVAILABLE                         the cooldown ends
 * </pre>
 * Administrative actions (reset, force capture, revocation after a faction disbands) may move
 * any state to AVAILABLE, COOLDOWN or CONTROLLED; they are checked with
 * {@link #canTransitionAdministratively}.
 */
public enum CaptureState {
    AVAILABLE,
    CAPTURING,
    CONTESTED,
    CONTROLLED,
    COOLDOWN,
    EXPIRED;

    private static final Map<CaptureState, Set<CaptureState>> LEGAL = Map.of(
            AVAILABLE, EnumSet.of(CAPTURING),
            CAPTURING, EnumSet.of(CONTESTED, CONTROLLED, AVAILABLE),
            CONTESTED, EnumSet.of(CAPTURING, CONTROLLED, AVAILABLE),
            CONTROLLED, EnumSet.of(CAPTURING, EXPIRED),
            EXPIRED, EnumSet.of(COOLDOWN, AVAILABLE),
            COOLDOWN, EnumSet.of(AVAILABLE));

    /** @return whether gameplay may move from this state to {@code next} (staying put is always legal) */
    public boolean canTransitionTo(CaptureState next) {
        return this == next || LEGAL.get(this).contains(next);
    }

    /** @return whether an administrative action may move from this state to {@code next} */
    public boolean canTransitionAdministratively(CaptureState next) {
        return next == AVAILABLE || next == COOLDOWN || next == CONTROLLED || canTransitionTo(next);
    }

    /** @return whether a capture attempt is in progress */
    public boolean capturing() {
        return this == CAPTURING || this == CONTESTED;
    }
}
