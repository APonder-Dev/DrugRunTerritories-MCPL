/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.ui;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.territory.CaptureEvent;
import dev.aponder.drugrun.territories.territory.CaptureListener;
import dev.aponder.drugrun.territories.territory.CaptureZone;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Broadcasts capture events with configurable messages, sounds and particles.
 *
 * <p>Starts and contests can flap when players hover at the zone edge, so each territory
 * announces them at most once per {@code announcements.repeat-cooldown-seconds}. A failure is
 * only announced when its start was. Completions, defences, expiries and revocations are always
 * announced. The contested sound plays only to players inside that territory.
 */
public final class Announcer implements CaptureListener {

    private final Messages messages;
    private final FactionNames names;
    private final Supplier<Settings> settings;
    private final Logger logger;
    private final LongSupplier clock;
    /** everyone who hears server-wide announcements: the server (all players and the console) in production */
    private final Supplier<? extends Audience> everyone;
    /** "territory|kind" -> epoch millis of the last announcement */
    private final Map<String, Long> lastAnnounced = new HashMap<>();
    /** territories whose current capture start was not announced */
    private final Set<String> quietStarts = new HashSet<>();

    public Announcer(Messages messages, FactionNames names, Supplier<Settings> settings, Logger logger) {
        this(messages, names, settings, logger, System::currentTimeMillis, Bukkit::getServer);
    }

    /** For tests: a fixed clock, and an audience that stands in for the whole server. */
    Announcer(Messages messages, FactionNames names, Supplier<Settings> settings, Logger logger, LongSupplier clock,
            Supplier<? extends Audience> everyone) {
        this.messages = messages;
        this.names = names;
        this.settings = settings;
        this.logger = logger;
        this.clock = clock;
        this.everyone = everyone;
    }

    /** @return whether this kind of announcement for the territory is outside its repeat cooldown (and records it) */
    private boolean due(String territoryId, String kind, Settings.Announcements cfg) {
        long now = clock.getAsLong();
        String key = territoryId + "|" + kind;
        Long last = lastAnnounced.get(key);
        if (last != null && now - last < cfg.repeatCooldown().toMillis()) {
            return false;
        }
        lastAnnounced.put(key, now);
        return true;
    }

    @Override
    public void onCaptureEvent(TerritoryDefinition territory, CaptureEvent event, TerritoryState state) {
        Settings.Announcements cfg = settings.get().announcements();
        var t = Messages.mini("territory", territory.displayName());
        switch (event) {
            case CaptureEvent.Started s when cfg.captureStarted() -> {
                if (!due(territory.id(), "start", cfg)) {
                    quietStarts.add(territory.id());
                    return;
                }
                quietStarts.remove(territory.id());
                broadcast(s.challenge() ? "capture.challenge-started" : "capture.started",
                        t, Messages.text("faction", names.name(s.factionId())), Messages.text("owner", names.name(state.ownerFactionId())));
            }
            case CaptureEvent.Contested c when cfg.captureContested() -> {
                if (!due(territory.id(), "contested", cfg)) {
                    return;
                }
                broadcast("capture.contested", t, Messages.text("faction", names.name(c.capturingFactionId())));
                localSound(territory, cfg.contestedSound(), cfg);
            }
            case CaptureEvent.Completed c when cfg.captureCompleted() -> {
                broadcast("capture.completed", t, Messages.text("faction", names.name(c.factionId())));
                sound(cfg.completedSound(), cfg);
                particles(territory, cfg);
            }
            case CaptureEvent.Failed f when cfg.captureStarted() && !quietStarts.remove(territory.id()) -> broadcast("capture.failed." + f.reason().name().toLowerCase(Locale.ROOT),
                    t, Messages.text("faction", names.name(f.factionId())));
            case CaptureEvent.Defended d when cfg.captureCompleted() -> broadcast("capture.defended", t,
                    Messages.text("faction", names.name(d.ownerFactionId())), Messages.text("challenger", names.name(d.challengerFactionId())));
            case CaptureEvent.Expired e when cfg.territoryExpired() -> broadcast("capture.expired", t,
                    Messages.text("faction", names.name(e.formerOwnerId())));
            case CaptureEvent.Revoked r when cfg.territoryExpired() -> broadcast("capture.revoked", t,
                    Messages.text("faction", names.name(r.formerOwnerId())));
            default -> {
            }
        }
    }

    private void broadcast(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        if (messages.has(key)) {
            everyone.get().sendMessage(messages.get(key, tags));
        }
    }

    private void sound(String name, Settings.Announcements cfg) {
        Sound sound = parseSound(name, cfg);
        if (sound != null) {
            everyone.get().playSound(sound);
        }
    }

    /** Plays a sound only to players inside the territory's region. */
    private void localSound(TerritoryDefinition territory, String name, Settings.Announcements cfg) {
        Sound sound = parseSound(name, cfg);
        World world = Bukkit.getWorld(territory.world());
        if (sound == null || world == null) {
            return;
        }
        for (org.bukkit.entity.Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (territory.region().contains(world.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
                p.playSound(sound);
            }
        }
    }

    private Sound parseSound(String name, Settings.Announcements cfg) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Sound.sound(Key.key(name.trim().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, cfg.volume(), cfg.pitch());
        } catch (RuntimeException e) {
            logger.warning("Invalid sound " + name);
            return null;
        }
    }

    private void particles(TerritoryDefinition territory, Settings.Announcements cfg) {
        if (!cfg.particles() || cfg.particleCount() <= 0) {
            return;
        }
        World world = Bukkit.getWorld(territory.world());
        if (world == null) {
            return;
        }
        Particle particle;
        try {
            particle = Particle.valueOf(cfg.particleType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid particle " + cfg.particleType());
            return;
        }
        if (particle.getDataType() != Void.class) {
            return;
        }
        CaptureZone z = territory.captureZone();
        world.spawnParticle(particle, new Location(world, z.x(), z.y() + 1, z.z()), cfg.particleCount(), z.radius() / 2, 1, z.radius() / 2, 0);
    }
}
