/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Finds eligible capturers with {@code World#getNearbyPlayers}, which only searches entity
 * sections near the zone, so there is no per-tick scan of every online player.
 *
 * <p>A player counts when they are alive, not a spectator (or creative, if configured), not
 * vanished, not AFK, hold {@code drugrun.territories.capture}, belong to a player faction, and
 * stand inside the capture cylinder. Disconnected players are simply not returned by the world.
 */
public final class BukkitParticipantSource implements ParticipantSource {

    /** Permission required to count towards captures. */
    public static final String CAPTURE_PERMISSION = "drugrun.territories.capture";

    private final FactionProvider factions;
    private final AfkTracker afk;
    private final Supplier<Settings.Capture> settings;

    public BukkitParticipantSource(FactionProvider factions, AfkTracker afk, Supplier<Settings.Capture> settings) {
        this.factions = factions;
        this.afk = afk;
        this.settings = settings;
    }

    @Override
    public Map<String, Integer> count(TerritoryDefinition territory, long now) {
        World world = Bukkit.getWorld(territory.world());
        if (world == null) {
            return Map.of();
        }
        CaptureZone zone = territory.captureZone();
        Location center = new Location(world, zone.x(), zone.y(), zone.z());
        if (!world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            return Map.of();
        }
        Settings.Capture cfg = settings.get();
        Map<String, Integer> counts = new HashMap<>();
        for (Player player : world.getNearbyPlayers(center, zone.radius())) {
            Location l = player.getLocation();
            if (!zone.contains(l.getX(), l.getY(), l.getZ()) || !eligible(player, cfg)) {
                continue;
            }
            if (afk.isAfk(player.getUniqueId(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), now,
                    cfg.afkTimeout().toMillis())) {
                continue;
            }
            Optional<FactionRef> faction = factions.factionOf(player.getUniqueId());
            faction.filter(FactionRef::normal).ifPresent(f -> counts.merge(f.id(), 1, Integer::sum));
        }
        return counts;
    }

    static boolean eligible(Player player, Settings.Capture cfg) {
        if (!player.isOnline() || player.isDead() || player.getHealth() <= 0) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SPECTATOR || (cfg.excludeCreative() && mode == GameMode.CREATIVE)) {
            return false;
        }
        if (player.hasMetadata("vanished") && player.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean())) {
            return false;
        }
        return player.hasPermission(CAPTURE_PERMISSION);
    }
}
