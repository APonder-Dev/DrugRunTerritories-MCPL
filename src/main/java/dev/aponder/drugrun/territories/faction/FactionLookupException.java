/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

/**
 * The faction plugin could not answer a question (disabled, reloading, or it threw).
 * Callers must fail closed.
 */
public final class FactionLookupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FactionLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
