/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.Set;
import java.util.UUID;

/** Something noteworthy the capture engine decided. The service turns these into messages, rewards and storage. */
public sealed interface CaptureEvent {

    String territoryId();

    /** A faction began capturing. {@code challenge} is true when another faction owned the territory. */
    record Started(String territoryId, String factionId, boolean challenge) implements CaptureEvent {
    }

    /** Other factions entered the zone during a capture. */
    record Contested(String territoryId, String capturingFactionId, Set<String> contestingFactionIds) implements CaptureEvent {
    }

    /** The zone is clear of other factions again and the capture continues. */
    record Resumed(String territoryId, String capturingFactionId) implements CaptureEvent {
    }

    /** A capture completed and ownership changed. */
    record Completed(String territoryId, String factionId, String previousOwnerId, UUID ownershipId) implements CaptureEvent {
    }

    /** Why a capture attempt ended without success. */
    enum FailReason {
        /** The capturing faction left or dropped below the minimum and progress ran out. */
        ABANDONED,
        /** The capturing faction reached its territory limit before completing. */
        LIMIT_REACHED,
        /** The capturing faction no longer exists or is no longer allowed. */
        INELIGIBLE
    }

    /** A capture attempt ended without success. */
    record Failed(String territoryId, String factionId, FailReason reason) implements CaptureEvent {
    }

    /** A challenge failed while the owner's players were contesting it. */
    record Defended(String territoryId, String ownerFactionId, String challengerFactionId) implements CaptureEvent {
    }

    /** Ownership ran out. */
    record Expired(String territoryId, String formerOwnerId) implements CaptureEvent {
    }

    /** Ownership was removed because the owning faction disbanded or an administrator acted. */
    record Revoked(String territoryId, String formerOwnerId) implements CaptureEvent {
    }

    /** The territory can be captured again. */
    record Available(String territoryId) implements CaptureEvent {
    }
}
