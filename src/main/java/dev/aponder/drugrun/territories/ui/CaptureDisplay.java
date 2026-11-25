/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.ui;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.territory.CaptureRules;
import dev.aponder.drugrun.territories.territory.CaptureState;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * One BossBar per territory, shown to players inside its region, plus an ActionBar for players
 * standing in the capture zone. Updated once per capture tick (not every server tick), and the
 * number of bars is bounded by the number of territories. Main thread only.
 */
public final class CaptureDisplay {

    private final TerritoryService territories;
    private final Messages messages;
    private final FactionNames names;
    private final LongSupplier clock;
    private final Map<String, BossBar> bars = new HashMap<>();
    /** player -> territory whose bar they currently see */
    private final Map<UUID, String> viewing = new HashMap<>();

    public CaptureDisplay(TerritoryService territories, Messages messages, FactionNames names, LongSupplier clock) {
        this.territories = territories;
        this.messages = messages;
        this.names = names;
        this.clock = clock;
    }

    /** Refreshes every bar and its viewers. */
    public void update() {
        Settings cfg = territories.settings();
        TerritoryRegistry registry = territories.registry();
        if (!cfg.announcements().bossBar() && !cfg.announcements().actionBar()) {
            clear();
            return;
        }
        long now = clock.getAsLong();
        Set<String> live = new HashSet<>();
        for (TerritoryDefinition def : registry.enabled()) {
            live.add(def.id());
            territories.state(def.id()).ifPresent(state -> render(def, state, now));
        }
        bars.keySet().removeIf(id -> {
            if (!live.contains(id)) {
                hideFromAll(id);
                return true;
            }
            return false;
        });

        Set<UUID> seen = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Player player : world.getPlayers()) {
                seen.add(player.getUniqueId());
                Location l = player.getLocation();
                Optional<TerritoryDefinition> here = registry.at(world.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                String now2 = here.map(TerritoryDefinition::id).orElse(null);
                String before = viewing.get(player.getUniqueId());
                if (before != null && !before.equals(now2)) {
                    BossBar old = bars.get(before);
                    if (old != null) {
                        player.hideBossBar(old);
                    }
                    viewing.remove(player.getUniqueId());
                }
                if (now2 != null && cfg.announcements().bossBar()) {
                    BossBar bar = bars.get(now2);
                    if (bar != null && !now2.equals(before)) {
                        player.showBossBar(bar);
                    }
                    viewing.put(player.getUniqueId(), now2);
                }
                if (here.isPresent() && cfg.announcements().actionBar()) {
                    actionBar(player, here.get(), l);
                }
            }
        }
        viewing.keySet().retainAll(seen);
    }

    private void render(TerritoryDefinition def, TerritoryState state, long now) {
        CaptureRules rules = def.rules(territories.settings().capture());
        TagResolver[] tags = {
                Messages.mini("territory", def.displayName()),
                Messages.text("owner", names.name(state.ownerFactionId())),
                Messages.text("capturer", names.name(state.capturingFactionId())),
                Messages.text("percent", FactionNames.percent(state.progressSeconds(), rules.durationSeconds())),
                Messages.text("remaining", FactionNames.duration((long) ((rules.durationSeconds() - state.progressSeconds()) * 1000))),
                Messages.text("expires", FactionNames.duration(state.ownedUntil() - now)),
                Messages.text("cooldown", FactionNames.duration(state.cooldownUntil() - now))
        };
        String key;
        BossBar.Color color;
        float progress;
        switch (state.state()) {
            case CAPTURING -> {
                key = "bossbar.capturing";
                color = BossBar.Color.YELLOW;
                progress = (float) (state.progressSeconds() / rules.durationSeconds());
            }
            case CONTESTED -> {
                key = "bossbar.contested";
                color = BossBar.Color.RED;
                progress = (float) (state.progressSeconds() / rules.durationSeconds());
            }
            case CONTROLLED -> {
                key = "bossbar.controlled";
                color = BossBar.Color.GREEN;
                progress = 1f;
            }
            case COOLDOWN, EXPIRED -> {
                key = "bossbar.cooldown";
                color = BossBar.Color.WHITE;
                progress = 0f;
            }
            default -> {
                key = "bossbar.available";
                color = BossBar.Color.BLUE;
                progress = 0f;
            }
        }
        progress = Math.max(0f, Math.min(1f, Float.isNaN(progress) ? 0f : progress));
        BossBar bar = bars.computeIfAbsent(def.id(), id -> BossBar.bossBar(messages.get(key, tags), 0f, color, BossBar.Overlay.PROGRESS));
        bar.name(messages.get(key, tags));
        bar.progress(progress);
        bar.color(color);
    }

    private void actionBar(Player player, TerritoryDefinition def, Location l) {
        if (!def.captureZone().contains(l.getX(), l.getY(), l.getZ())) {
            return;
        }
        territories.state(def.id()).ifPresent(state -> {
            CaptureRules rules = def.rules(territories.settings().capture());
            String key = state.state() == CaptureState.CONTESTED ? "actionbar.contested"
                    : state.state() == CaptureState.CAPTURING ? "actionbar.capturing" : null;
            if (key != null) {
                messages.actionBar(player, key,
                        Messages.mini("territory", def.displayName()),
                        Messages.text("capturer", names.name(state.capturingFactionId())),
                        Messages.text("percent", FactionNames.percent(state.progressSeconds(), rules.durationSeconds())));
            }
        });
    }

    /** Removes a player's bar (quit). */
    public void forget(Player player) {
        String id = viewing.remove(player.getUniqueId());
        if (id != null && bars.containsKey(id)) {
            player.hideBossBar(bars.get(id));
        }
    }

    /** Hides and drops every bar (disable or reload). */
    public void clear() {
        for (String id : Set.copyOf(bars.keySet())) {
            hideFromAll(id);
        }
        bars.clear();
        viewing.clear();
    }

    private void hideFromAll(String territoryId) {
        BossBar bar = bars.get(territoryId);
        if (bar == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
        }
        viewing.values().removeIf(territoryId::equals);
    }
}
