/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

/** Receives capture events on the main thread, after the new state is in place. */
@FunctionalInterface
public interface CaptureListener {

    /**
     * @param territory the territory's definition
     * @param event what happened
     * @param state the territory's state after the event's tick
     */
    void onCaptureEvent(TerritoryDefinition territory, CaptureEvent event, TerritoryState state);
}
