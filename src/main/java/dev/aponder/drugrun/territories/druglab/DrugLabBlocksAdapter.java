/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.DrugLabAPI;
import dev.aponder.druglab.plant.Plant;
import dev.aponder.druglab.station.Station;
import dev.aponder.drugrun.territories.raid.DrugLabBlocks;
import org.bukkit.Location;
import org.bukkit.block.Block;

/** {@link DrugLabBlocks} using DrugLab's public API ({@code DrugLabAPI.getPlant}, {@code getStation}). */
final class DrugLabBlocksAdapter implements DrugLabBlocks {

    @Override
    public Location ownerBase(Block block) {
        if (!DrugLabAPI.isEnabled()) {
            return null;
        }
        Plant plant = DrugLabAPI.getPlant(block);
        if (plant != null) {
            Block base = plant.base().block();
            return base != null ? base.getLocation() : block.getLocation();
        }
        Station station = DrugLabAPI.getStation(block);
        return station != null ? block.getLocation() : null;
    }
}
