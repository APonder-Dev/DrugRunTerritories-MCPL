/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Filters explosion block lists with {@link RaidPolicy}. Runs at LOW priority so DrugLab (HIGH)
 * only sees the blocks that are allowed to be destroyed, and before monitoring plugins record
 * the result. The explosion itself is never cancelled, so cannons keep working where FactionsUUID
 * permits them.
 */
public final class ExplosionListener implements Listener {

    private final Supplier<TerritoryRegistry> registry;
    private final Supplier<Settings> settings;
    private final FactionProvider factions;
    private final Supplier<DrugLabBlocks> drugLab;

    public ExplosionListener(Supplier<TerritoryRegistry> registry, Supplier<Settings> settings, FactionProvider factions,
                             Supplier<DrugLabBlocks> drugLab) {
        this.registry = registry;
        this.settings = settings;
        this.factions = factions;
        this.drugLab = drugLab;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filter(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filter(event.blockList());
    }

    /** Removes protected blocks from {@code blocks} in place. */
    void filter(List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        Settings cfg = settings.get();
        TerritoryRegistry reg = registry.get();
        DrugLabBlocks lab = drugLab.get();
        boolean arenas = cfg.protection().preventArenaGriefing();
        // Explosions touch a handful of chunks; look up each owner's raid state once.
        Map<String, RaidState> stateByChunk = new HashMap<>();
        Map<String, Boolean> unknownByChunk = new HashMap<>();
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            boolean inArena = arenas && inArena(reg, block);
            RaidState state = null;
            Location base = inArena ? null : lab.ownerBase(block);
            if (base != null) {
                String key = base.getWorld().getName() + ':' + (base.getBlockX() >> 4) + ':' + (base.getBlockZ() >> 4);
                if (!unknownByChunk.containsKey(key)) {
                    try {
                        stateByChunk.put(key, factions.raidStateAt(base));
                        unknownByChunk.put(key, false);
                    } catch (FactionLookupException e) {
                        unknownByChunk.put(key, true);
                    }
                }
                state = stateByChunk.get(key);
            }
            if (RaidPolicy.decide(inArena, base != null, state, cfg.raiding()) == RaidPolicy.Decision.PROTECT) {
                it.remove();
            }
        }
    }

    private static boolean inArena(TerritoryRegistry reg, Block block) {
        Optional<TerritoryDefinition> def = reg.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return def.isPresent() && def.get().protection() == TerritoryDefinition.Protection.ARENA;
    }
}
