/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import org.bukkit.Location;
import org.bukkit.block.Block;

/** Identifies DrugLab blocks without the raid code depending on DrugLab classes. */
@FunctionalInterface
public interface DrugLabBlocks {

    /**
     * @return the base location of the DrugLab plant or station this block belongs to (including a
     *         plant's soil block), or {@code null} when it is not a DrugLab block
     */
    Location ownerBase(Block block);

    /** Used when DrugLab is not installed or its integration is disabled. */
    DrugLabBlocks NONE = block -> null;
}
