/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.event.PlantDestroyEvent;
import dev.aponder.druglab.api.event.ShopTransactionCompleteEvent;
import dev.aponder.druglab.api.event.ShopTransactionEvent;
import dev.aponder.druglab.api.event.StationProcessCompleteEvent;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Listeners for the DrugLab hooks added after DrugLab 1.0.0 ({@code PlantDestroyEvent},
 * {@code ShopTransactionCompleteEvent}, {@code StationProcessCompleteEvent}). Registered only when
 * the installed DrugLab has them; see {@link DrugLabIntegration#hasExtendedApi()}.
 */
final class DrugLabHookListener implements Listener {

    private final FactionProvider factions;
    private final ProgressionService progression;
    private final Supplier<Settings> settings;

    DrugLabHookListener(FactionProvider factions, ProgressionService progression, Supplier<Settings> settings) {
        this.factions = factions;
        this.progression = progression;
        this.settings = settings;
    }

    /** Explosion drops follow {@code raiding.plant-explosion-drops}. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(PlantDestroyEvent event) {
        if (event.getCause() == PlantDestroyEvent.Cause.EXPLOSION) {
            event.setExperience(PlantAuthorizationListener.applyExplosionDrops(event.getDrops(), event.getExperience(),
                    settings.get().raiding()));
        }
    }

    /** Paid dealer sales count towards faction sale statistics and milestones. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSaleCompleted(ShopTransactionCompleteEvent event) {
        if (event.getType() != ShopTransactionEvent.Type.SELL || event.getAmount() <= 0 || !(event.getPrice() >= 0)) {
            return;
        }
        try {
            factions.factionOf(event.getPlayer().getUniqueId())
                    .ifPresent(f -> progression.recordSale(f.id(), event.getAmount(), event.getPrice()));
        } catch (FactionLookupException e) {
            // No faction data, no statistic.
        }
    }

    /** Collected processing jobs count towards faction processing statistics. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProcessed(StationProcessCompleteEvent event) {
        Player collector = event.getCollector();
        if (collector == null) {
            return;
        }
        try {
            factions.factionOf(collector.getUniqueId()).ifPresent(f -> progression.recordProcessing(f.id()));
        } catch (FactionLookupException e) {
            // No faction data, no statistic.
        }
    }
}
