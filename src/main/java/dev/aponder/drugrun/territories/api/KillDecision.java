/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

import java.util.Objects;

/**
 * Answer to "may this kill earn a Loot Key?" from {@link LootKeyFactionPolicy}.
 *
 * @param outcome the decision
 * @param reason machine-readable reason, stable across versions
 * @param relation relation between killer and victim at the time of the kill
 */
public record KillDecision(Outcome outcome, Reason reason, FactionRelation relation) {

    /** The decision itself. */
    public enum Outcome {
        /** Faction rules allow a key. DrugRunCore still applies its own checks. */
        ALLOW,
        /** Faction rules forbid a key. */
        DENY,
        /**
         * Faction state could not be read (for example FactionsUUID is disabled). Callers must
         * treat this as {@link #DENY}: the policy is fail-closed.
         */
        UNAVAILABLE
    }

    /** Why the decision was made. */
    public enum Reason {
        SELF_KILL,
        SAME_FACTION,
        ALLIED_FACTION,
        ENEMY_FACTION,
        NEUTRAL,
        SAFEZONE,
        WARZONE,
        WILDERNESS,
        TERRITORY_OVERRIDE,
        FACTIONS_UNAVAILABLE
    }

    public KillDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(relation, "relation");
    }

    /** @return {@code true} only for {@link Outcome#ALLOW}; {@code UNAVAILABLE} counts as denied */
    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }
}
