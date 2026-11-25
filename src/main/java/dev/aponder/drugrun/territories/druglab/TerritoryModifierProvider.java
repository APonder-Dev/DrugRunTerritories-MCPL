/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.modifier.ModifierProvider;
import dev.aponder.druglab.plant.Plant;
import dev.aponder.druglab.station.Station;
import dev.aponder.druglab.station.StationRecipe;
import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Supplies the CROP_GROWTH and PROCESSING_SPEED territory bonuses to DrugLab's
 * {@code ModifierProvider} hook. The plant's or station's owner must belong to a faction that
 * currently controls a territory with that bonus; ownership and membership are checked on every
 * call, so a lost territory or a faction change takes effect immediately.
 *
 * <p>DrugLab clamps and caps the value itself; this provider returns at most the territory
 * stacking hard cap. Off the main thread (not expected on Paper) it returns 1.0, because faction
 * and territory state are main-thread data.
 */
final class TerritoryModifierProvider implements ModifierProvider {

    private final FactionProvider factions;
    private final TerritoryService territories;
    private final BooleanSupplier primaryThread;

    TerritoryModifierProvider(FactionProvider factions, TerritoryService territories, BooleanSupplier primaryThread) {
        this.factions = factions;
        this.territories = territories;
        this.primaryThread = primaryThread;
    }

    @Override
    public double growthMultiplier(Plant plant) {
        return multiplier(plant == null ? null : plant.owner(), BonusType.CROP_GROWTH);
    }

    @Override
    public double processingMultiplier(Station station, StationRecipe recipe) {
        return multiplier(station == null ? null : station.owner(), BonusType.PROCESSING_SPEED);
    }

    double multiplier(UUID owner, BonusType type) {
        if (owner == null || !primaryThread.getAsBoolean()) {
            return 1.0;
        }
        try {
            Optional<FactionRef> faction = factions.factionOf(owner);
            return faction.map(f -> territories.bonusMultiplier(f.id(), type)).orElse(1.0);
        } catch (FactionLookupException e) {
            return 1.0;
        }
    }
}
