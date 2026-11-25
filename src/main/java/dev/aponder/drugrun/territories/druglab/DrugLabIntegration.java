/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.raid.DrugLabBlocks;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.ui.Messages;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import dev.aponder.druglab.api.modifier.ModifierProvider;

/**
 * Entry point for the DrugLab integration. This is the only class outside this package that
 * mentions DrugLab, and it only touches DrugLab classes after confirming DrugLab is enabled, so
 * DrugRunTerritories loads fine without DrugLab installed.
 */
public final class DrugLabIntegration {

    private DrugLabIntegration() {
    }

    /** @return whether the DrugLab plugin is installed and enabled */
    public static boolean present() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("DrugLab");
        return plugin != null && plugin.isEnabled();
    }

    /**
     * @return whether the installed DrugLab has the hooks added after 1.0.0 (ModifierProvider,
     *         PlantDestroyEvent, ShopTransactionCompleteEvent, StationProcessCompleteEvent)
     */
    public static boolean hasExtendedApi() {
        ClassLoader loader = DrugLabIntegration.class.getClassLoader();
        for (String name : new String[] {"dev.aponder.druglab.api.modifier.ModifierProvider",
                "dev.aponder.druglab.api.event.PlantDestroyEvent", "dev.aponder.druglab.api.event.ShopTransactionCompleteEvent",
                "dev.aponder.druglab.api.event.StationProcessCompleteEvent"}) {
            try {
                Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Registers DrugLab listeners, and with a DrugLab that has the extended API also the growth and
     * processing bonus provider and the sale/processing statistics listener.
     *
     * @param extraBuildCheck additional build permission (WorldGuard) applied to plant footprints
     *
     * @return block lookups for the raid layer
     */
    public static DrugLabBlocks register(Plugin owner, FactionProvider factions, TerritoryService territories,
                                         Supplier<Settings> settings, Supplier<TerritoryRegistry> registry, Messages messages,
                                         ProgressionService progression, Logger logger,
                                         BiPredicate<Player, Location> extraBuildCheck) {
        Bukkit.getPluginManager().registerEvents(
                new PlantAuthorizationListener(factions, settings, registry, messages, progression, logger, extraBuildCheck), owner);
        Bukkit.getPluginManager().registerEvents(new StationAccessListener(factions, settings, messages), owner);
        Bukkit.getPluginManager().registerEvents(new DealerBonusListener(factions, territories, settings, logger), owner);
        if (hasExtendedApi()) {
            Bukkit.getPluginManager().registerEvents(new DrugLabHookListener(factions, progression, settings), owner);
            Bukkit.getServicesManager().register(ModifierProvider.class,
                    new TerritoryModifierProvider(factions, territories, Bukkit::isPrimaryThread), owner, ServicePriority.Normal);
        } else {
            logger.warning("This DrugLab build has no ModifierProvider/PlantDestroyEvent API: growth and processing bonuses and"
                    + " sale/processing statistics are inactive. Update DrugLab to enable them.");
        }
        return new DrugLabBlocksAdapter();
    }
}
