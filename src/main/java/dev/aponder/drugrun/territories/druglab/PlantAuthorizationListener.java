/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.DrugLabAPI;
import dev.aponder.druglab.api.event.FertilizerUseEvent;
import dev.aponder.druglab.api.event.PlantGrowEvent;
import dev.aponder.druglab.api.event.PlantHarvestEvent;
import dev.aponder.druglab.api.event.PlantPlaceEvent;
import dev.aponder.druglab.plant.Plant;
import dev.aponder.druglab.plant.PlantType;
import dev.aponder.druglab.util.BlockKey;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.ui.Messages;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * Faction-aware rules for DrugLab plants, using only DrugLab's public events.
 *
 * <ul>
 *   <li><b>Planting</b>: every block the plant will ever occupy (all stages, plus soil) must be
 *       buildable by the planter and outside territory arenas. A plant is never allowed to start
 *       if a later stage would cross into protected land.</li>
 *   <li><b>Growth</b>: before each stage change, the blocks the new stage adds are re-checked
 *       against the land the plant's base is in. If a claim changed since planting and the new
 *       blocks would enter other land (or an arena), growth is frozen: nothing is placed, nothing
 *       is destroyed. Growth resumes if the land situation is fixed.</li>
 *   <li><b>Harvest and fertilizer</b>: need break/build access to the plant's base.</li>
 *   <li><b>Explosion harvests</b> (DrugLab fires these with no player): drops follow
 *       {@code raiding.plant-explosion-drops}.</li>
 * </ul>
 * Any faction lookup failure denies the action (fail closed).
 */
final class PlantAuthorizationListener implements Listener {

    private final FactionProvider factions;
    private final Supplier<Settings> settings;
    private final Supplier<TerritoryRegistry> registry;
    private final Messages messages;
    private final ProgressionService progression;
    private final Logger logger;
    private final BiPredicate<Player, Location> extraBuildCheck;

    PlantAuthorizationListener(FactionProvider factions, Supplier<Settings> settings, Supplier<TerritoryRegistry> registry,
                               Messages messages, ProgressionService progression, Logger logger,
                               BiPredicate<Player, Location> extraBuildCheck) {
        this.extraBuildCheck = extraBuildCheck;
        this.factions = factions;
        this.settings = settings;
        this.registry = registry;
        this.messages = messages;
        this.progression = progression;
        this.logger = logger;
    }

    // ---- planting ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlantPlaceEvent event) {
        Player player = event.getPlayer();
        Settings.DrugLab cfg = settings.get().druglab();
        Set<BlockKey> footprint = cfg.checkFullFootprint()
                ? PlantFootprint.allStages(event.getPlantType(), event.getBase(), event.getRotation(), player.getUniqueId())
                : Set.of(BlockKey.of(event.getBase()));
        try {
            for (BlockKey key : footprint) {
                Location loc = location(key);
                if (loc == null || inArena(loc) || !factions.canBuild(player, loc) || !extraBuildCheck.test(player, loc)) {
                    event.setCancelled(true);
                    messages.send(player, "druglab.plant-denied");
                    return;
                }
            }
        } catch (FactionLookupException e) {
            event.setCancelled(true);
            messages.send(player, "druglab.factions-unavailable");
        }
    }

    // ---- growth -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(PlantGrowEvent event) {
        if (!settings.get().druglab().freezeOnUnauthorizedGrowth()) {
            return;
        }
        Plant plant = event.getPlant();
        PlantType type = DrugLabAPI.getPlantType(plant.typeId());
        if (type == null) {
            return;
        }
        Location base = location(plant.base());
        if (base == null) {
            event.setCancelled(true);
            return;
        }
        try {
            FactionRef baseOwner = factions.factionAt(base);
            for (BlockKey key : PlantFootprint.newBlocks(plant, type, event.getNextStage())) {
                Location loc = location(key);
                if (loc == null || inArena(loc)) {
                    event.setCancelled(true);
                    return;
                }
                FactionRef owner = factions.factionAt(loc);
                boolean sameLand = owner.id().equals(baseOwner.id());
                if (!sameLand && owner.kind() != FactionRef.Kind.WILDERNESS) {
                    event.setCancelled(true);
                    if (settings.get().debug()) {
                        logger.info("Froze DrugLab plant " + plant.id() + ": next stage would enter " + owner.name());
                    }
                    return;
                }
            }
        } catch (FactionLookupException e) {
            event.setCancelled(true);
        }
    }

    // ---- harvest & fertilizer -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(PlantHarvestEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            // DrugLab 1.0.0 reports explosion destruction as a harvest with no player. Newer DrugLab
            // fires PlantDestroyEvent instead, handled by DrugLabHookListener.
            event.setExperience(applyExplosionDrops(event.getDrops(), event.getExperience(), settings.get().raiding()));
            return;
        }
        if (!settings.get().druglab().requireAccessToHarvest()) {
            return;
        }
        Location base = location(event.getPlant().base());
        try {
            if (base == null || !factions.canDestroy(player, base)) {
                event.setCancelled(true);
                messages.send(player, "druglab.harvest-denied");
            }
        } catch (FactionLookupException e) {
            event.setCancelled(true);
            messages.send(player, "druglab.factions-unavailable");
        }
    }

    /** Counts mature harvests by faction members once DrugLab has committed them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvestCompleted(PlantHarvestEvent event) {
        Player player = event.getPlayer();
        if (player == null || !event.isMature()) {
            return;
        }
        try {
            factions.factionOf(player.getUniqueId()).ifPresent(f -> progression.recordHarvest(f.id()));
        } catch (FactionLookupException e) {
            // No faction data, no statistic: fail closed for rewards.
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(FertilizerUseEvent event) {
        if (!settings.get().druglab().requireAccessToFertilize()) {
            return;
        }
        Player player = event.getPlayer();
        Location base = location(event.getPlant().base());
        try {
            if (base == null || !factions.canBuild(player, base)) {
                event.setCancelled(true);
                messages.send(player, "druglab.fertilize-denied");
            }
        } catch (FactionLookupException e) {
            event.setCancelled(true);
            messages.send(player, "druglab.factions-unavailable");
        }
    }

    /**
     * Applies {@code raiding.plant-explosion-drops} to a drop list in place. Drops are only removed
     * or shrunk, never added, so nothing can be duplicated.
     *
     * @return the experience to drop
     */
    static int applyExplosionDrops(List<ItemStack> drops, int experience, Settings.Raiding cfg) {
        switch (cfg.plantExplosionDrops()) {
            case NONE -> {
                drops.clear();
                return 0;
            }
            case REDUCED -> {
                Iterator<ItemStack> it = drops.iterator();
                while (it.hasNext()) {
                    ItemStack stack = it.next();
                    int keep = (int) Math.floor(stack.getAmount() * cfg.reducedDropFraction());
                    if (keep <= 0) {
                        it.remove();
                    } else {
                        stack.setAmount(keep);
                    }
                }
                return (int) Math.floor(experience * cfg.reducedDropFraction());
            }
            default -> {
                return experience;
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private boolean inArena(Location loc) {
        return registry.get().at(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                .map(def -> def.protection() == TerritoryDefinition.Protection.ARENA)
                .orElse(false);
    }

    private static Location location(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        return world == null ? null : new Location(world, key.x(), key.y(), key.z());
    }
}
