/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.plant.GrowthStage;
import dev.aponder.druglab.plant.Plant;
import dev.aponder.druglab.plant.PlantType;
import dev.aponder.druglab.util.BlockKey;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.Block;

/**
 * Computes the blocks a DrugLab plant occupies, using DrugLab's own {@code Plant#blocksOf}, so
 * rotations and multi-block stages match exactly what DrugLab will place.
 */
final class PlantFootprint {

    private PlantFootprint() {
    }

    /** @return every block the plant may occupy across all of its growth stages, plus its soil */
    static Set<BlockKey> allStages(PlantType type, Block base, int rotation, UUID owner) {
        Plant probe = new Plant(UUID.randomUUID(), type.id(), BlockKey.of(base), rotation, owner, 0, 0D, System.currentTimeMillis());
        Set<BlockKey> keys = new LinkedHashSet<>();
        keys.add(probe.soil());
        for (GrowthStage stage : type.stages()) {
            keys.addAll(probe.blocksOf(stage).keySet());
        }
        return keys;
    }

    /** @return blocks the plant will occupy at {@code nextStage} that it does not occupy now */
    static Set<BlockKey> newBlocks(Plant plant, PlantType type, int nextStage) {
        if (nextStage < 0 || nextStage >= type.stages().size()) {
            return Set.of();
        }
        Set<BlockKey> next = new LinkedHashSet<>(plant.blocksOf(type.stages().get(nextStage)).keySet());
        int current = Math.min(Math.max(plant.stage(), 0), type.stages().size() - 1);
        next.removeAll(plant.blocksOf(type.stages().get(current)).keySet());
        return next;
    }
}
