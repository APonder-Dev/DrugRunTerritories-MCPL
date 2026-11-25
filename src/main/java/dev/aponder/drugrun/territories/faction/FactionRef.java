/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import java.util.Objects;

/**
 * Lightweight, immutable reference to a faction. {@code id} is the faction plugin's stable
 * identifier (never the tag, which players can rename); {@code name} is the tag at lookup time,
 * for display only.
 */
public record FactionRef(String id, String name, Kind kind) {

    /** What sort of land owner this is. */
    public enum Kind {
        /** A player faction. */
        NORMAL,
        WILDERNESS,
        SAFEZONE,
        WARZONE
    }

    public FactionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    /** @return whether this is a player faction rather than a system area */
    public boolean normal() {
        return kind == Kind.NORMAL;
    }
}
