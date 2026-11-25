/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.Objects;
import java.util.UUID;

/**
 * Runtime state of one territory. Immutable: the capture engine returns a new instance per change,
 * so a snapshot can be handed to the database thread safely.
 *
 * <p>An owner may exist while the state is CAPTURING or CONTESTED: that is a challenge, and the
 * owner keeps control (and bonuses) until the challenger completes.
 *
 * @param ownerFactionId controlling faction, or {@code null}
 * @param ownerSince epoch millis the current ownership began
 * @param ownedUntil epoch millis ownership expires
 * @param cooldownUntil epoch millis before which the territory cannot be captured or challenged
 * @param capturingFactionId faction currently capturing, or {@code null}
 * @param progressSeconds capture progress in seconds
 * @param ownershipId unique id of the current ownership period; keys income payouts so they are never repeated
 * @param defendersSeen whether the owner's players contested the current challenge (not persisted)
 */
public record TerritoryState(
        String territoryId,
        CaptureState state,
        String ownerFactionId,
        long ownerSince,
        long ownedUntil,
        long cooldownUntil,
        String capturingFactionId,
        double progressSeconds,
        UUID ownershipId,
        boolean defendersSeen) {

    public TerritoryState {
        Objects.requireNonNull(territoryId, "territoryId");
        Objects.requireNonNull(state, "state");
        if (Double.isNaN(progressSeconds) || progressSeconds < 0) {
            progressSeconds = 0;
        }
    }

    /** @return a fresh, unowned, available territory */
    public static TerritoryState available(String territoryId) {
        return new TerritoryState(territoryId, CaptureState.AVAILABLE, null, 0, 0, 0, null, 0, null, false);
    }

    public boolean owned() {
        return ownerFactionId != null;
    }

    public TerritoryState withState(CaptureState next) {
        return new TerritoryState(territoryId, next, ownerFactionId, ownerSince, ownedUntil, cooldownUntil,
                capturingFactionId, progressSeconds, ownershipId, defendersSeen);
    }

    public TerritoryState withProgress(double seconds) {
        return new TerritoryState(territoryId, state, ownerFactionId, ownerSince, ownedUntil, cooldownUntil,
                capturingFactionId, Math.max(0, seconds), ownershipId, defendersSeen);
    }

    public TerritoryState withDefendersSeen(boolean seen) {
        return new TerritoryState(territoryId, state, ownerFactionId, ownerSince, ownedUntil, cooldownUntil,
                capturingFactionId, progressSeconds, ownershipId, seen);
    }

    /** @return this state with the capture attempt cleared */
    public TerritoryState withoutCapture(CaptureState next) {
        return new TerritoryState(territoryId, next, ownerFactionId, ownerSince, ownedUntil, cooldownUntil,
                null, 0, ownershipId, false);
    }

    /** @return this state with the owner cleared (capture attempt, if any, kept) */
    public TerritoryState withoutOwner() {
        return new TerritoryState(territoryId, state, null, 0, 0, cooldownUntil, capturingFactionId, progressSeconds, null,
                false);
    }

    /** @return this state with a new capture attempt by {@code factionId} */
    public TerritoryState capturingBy(String factionId) {
        return new TerritoryState(territoryId, CaptureState.CAPTURING, ownerFactionId, ownerSince, ownedUntil, cooldownUntil,
                factionId, 0, ownershipId, false);
    }

    /** @return a controlled state with a brand new ownership period */
    public TerritoryState controlledBy(String factionId, long now, long ownershipMillis, long cooldownMillis) {
        return new TerritoryState(territoryId, CaptureState.CONTROLLED, factionId, now, now + ownershipMillis,
                now + cooldownMillis, null, 0, UUID.randomUUID(), false);
    }

    /** @return this state with a new cooldown end */
    public TerritoryState withCooldownUntil(long until) {
        return new TerritoryState(territoryId, state, ownerFactionId, ownerSince, ownedUntil, until, capturingFactionId,
                progressSeconds, ownershipId, defendersSeen);
    }
}
