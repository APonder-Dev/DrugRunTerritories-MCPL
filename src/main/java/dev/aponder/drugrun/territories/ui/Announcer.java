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
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/** Broadcasts capture events with configurable messages, sounds and particles. */
public final class Announcer implements CaptureListener {

    private final Messages messages;
    private final FactionNames names;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public Announcer(Messages messages, FactionNames names, Supplier<Settings> settings, Logger logger) {
        this.messages = messages;
        this.names = names;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void onCaptureEvent(TerritoryDefinition territory, CaptureEvent event, TerritoryState state) {
        Settings.Announcements cfg = settings.get().announcements();
        var t = Messages.mini("territory", territory.displayName());
        switch (event) {
            case CaptureEvent.Started s when cfg.captureStarted() -> broadcast(s.challenge() ? "capture.challenge-started" : "capture.started",
                    t, Messages.text("faction", names.name(s.factionId())), Messages.text("owner", names.name(state.ownerFactionId())));
            case CaptureEvent.Contested c when cfg.captureContested() -> {
                broadcast("capture.contested", t, Messages.text("faction", names.name(c.capturingFactionId())));
                sound(cfg.contestedSound(), cfg);
            }
            case CaptureEvent.Completed c when cfg.captureCompleted() -> {
                broadcast("capture.completed", t, Messages.text("faction", names.name(c.factionId())));
                sound(cfg.completedSound(), cfg);
                particles(territory, cfg);
            }
            case CaptureEvent.Failed f when cfg.captureStarted() -> broadcast("capture.failed." + f.reason().name().toLowerCase(Locale.ROOT),
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
            Bukkit.getServer().sendMessage(messages.get(key, tags));
        }
    }

    private void sound(String name, Settings.Announcements cfg) {
        if (name == null || name.isBlank()) {
            return;
        }
        Key key;
        try {
            key = Key.key(name.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            logger.warning("Invalid sound " + name);
            return;
        }
        Sound sound = Sound.sound(key, Sound.Source.MASTER, cfg.volume(), cfg.pitch());
        Bukkit.getServer().playSound(sound);
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
