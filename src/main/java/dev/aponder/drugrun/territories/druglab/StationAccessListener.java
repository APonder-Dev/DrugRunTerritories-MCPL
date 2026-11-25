/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.DrugLabAPI;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.ui.Messages;
import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Requires FactionsUUID container access to open a DrugLab processing station. Runs at LOW
 * priority, before DrugLab's own HIGH handler; DrugLab skips stations whose interaction was
 * denied ({@code useInteractedBlock() == DENY}), so the station's input and output inventories
 * are never shown to an unauthorised player.
 *
 * <p>Station placement and removal are already block place/break actions that FactionsUUID
 * protects in claims; explosions are handled by the raid layer.
 */
final class StationAccessListener implements Listener {

    private final FactionProvider factions;
    private final Supplier<Settings> settings;
    private final Messages messages;

    StationAccessListener(FactionProvider factions, Supplier<Settings> settings, Messages messages) {
        this.factions = factions;
        this.settings = settings;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !settings.get().druglab().requireStationContainerAccess()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !DrugLabAPI.isEnabled() || DrugLabAPI.getStation(block) == null) {
            return;
        }
        boolean allowed;
        try {
            allowed = factions.canUseContainers(event.getPlayer(), block.getLocation());
        } catch (FactionLookupException e) {
            allowed = false;
        }
        if (!allowed) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            messages.send(event.getPlayer(), "druglab.station-denied");
        }
    }
}
