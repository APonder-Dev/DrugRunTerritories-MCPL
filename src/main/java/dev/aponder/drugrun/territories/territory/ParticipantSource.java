/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.Map;

/** Counts eligible players per faction inside a territory's capture zone. */
@FunctionalInterface
public interface ParticipantSource {

    /**
     * @return faction id to number of eligible players in the capture zone; factions with no
     *         eligible players are omitted
     */
    Map<String, Integer> count(TerritoryDefinition territory, long now);
}
