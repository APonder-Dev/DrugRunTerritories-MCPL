/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Reacts to FactionsUUID changes:
 * <ul>
 *   <li>Claims that would overlap a territory region are cancelled, so server territories and
 *       faction claims never compete for the same land.</li>
 *   <li>A disbanded faction loses its territories and capture attempts immediately.</li>
 * </ul>
 * Membership and relation changes need no handling: every capture tick, bonus and policy check
 * reads current data from FactionsUUID.
 */
public final class FactionChangeListener implements Listener {

    private final TerritoryService territories;
    private final Consumer<String> onDisband;

    /**
     * @param onDisband extra cleanup for a disbanded faction id (progression, caches)
     */
    public FactionChangeListener(TerritoryService territories, Consumer<String> onDisband) {
        this.territories = territories;
        this.onDisband = onDisband;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaim(LandClaimEvent event) {
        FLocation loc = event.getLocation();
        if (territories.registry().touchesChunk(loc.worldName(), loc.x(), loc.z())) {
            event.setCancelled(true);
            if (event.getFPlayer() != null) {
                event.getFPlayer().sendRichMessage("<red>This land belongs to a server territory and cannot be claimed.</red>");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(FactionDisbandEvent event) {
        disbanded(String.valueOf(event.getFaction().id()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAutoDisband(FactionAutoDisbandEvent event) {
        disbanded(String.valueOf(event.getFaction().id()));
    }

    private void disbanded(String factionId) {
        territories.factionRemoved(factionId);
        onDisband.accept(factionId);
    }
}
