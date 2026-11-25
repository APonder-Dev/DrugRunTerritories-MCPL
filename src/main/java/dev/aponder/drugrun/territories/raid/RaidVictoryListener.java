/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.ui.Messages;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

/**
 * Detects validated raid victories: an enemy faction's kill is the one that makes the victim's
 * faction raidable in FactionsUUID. The check happens one tick after the death, once FactionsUUID
 * has applied its DTR loss. Credit is limited by {@code progression.raid-victory-cooldown-hours}
 * per attacker/defender pair, so repeatedly dropping and regaining raidability cannot be farmed.
 */
public final class RaidVictoryListener implements Listener {

    private final Plugin plugin;
    private final FactionProvider factions;
    private final ProgressionService progression;
    private final Supplier<Settings> settings;
    private final Messages messages;
    private final LongSupplier clock;

    public RaidVictoryListener(Plugin plugin, FactionProvider factions, ProgressionService progression, Supplier<Settings> settings,
                               Messages messages, LongSupplier clock) {
        this.plugin = plugin;
        this.factions = factions;
        this.progression = progression;
        this.settings = settings;
        this.messages = messages;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return;
        }
        Optional<FactionRef> defender;
        Optional<FactionRef> attacker;
        RaidState before;
        try {
            defender = factions.factionOf(victim.getUniqueId());
            attacker = factions.factionOf(killer.getUniqueId());
            if (defender.isEmpty() || attacker.isEmpty()
                    || factions.relation(attacker.get(), defender.get()) != FactionRelation.ENEMY) {
                return;
            }
            before = factions.raidState(defender.get());
        } catch (FactionLookupException e) {
            return;
        }
        if (before == RaidState.VULNERABLE) {
            return;
        }
        FactionRef def = defender.get();
        FactionRef att = attacker.get();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (factions.raidState(def) != RaidState.VULNERABLE) {
                    return;
                }
            } catch (FactionLookupException e) {
                return;
            }
            if (settings.get().announcements().factionRaidable()) {
                Bukkit.getServer().sendMessage(messages.get("raid.faction-raidable",
                        Messages.text("defender", def.name()), Messages.text("attacker", att.name())));
            }
            progression.tryRaidVictory(att.id(), def.id(), clock.getAsLong());
        });
    }
}
