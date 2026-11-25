/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cheap AFK detection for capture zones. Instead of listening to every movement packet, it
 * compares a player's position and head rotation each time the capture zone is evaluated. Only
 * players standing in capture zones are ever tracked. Main thread only.
 */
public final class AfkTracker {

    private record Pose(double x, double y, double z, float yaw, float pitch, long lastActive) {
        boolean sameAs(double nx, double ny, double nz, float nyaw, float npitch) {
            return x == nx && y == ny && z == nz && yaw == nyaw && pitch == npitch;
        }
    }

    private final Map<UUID, Pose> poses = new HashMap<>();

    /**
     * Updates the player's pose and reports whether they have been idle for at least {@code timeoutMillis}.
     * A timeout of 0 disables detection.
     */
    public boolean isAfk(UUID player, double x, double y, double z, float yaw, float pitch, long now, long timeoutMillis) {
        Pose previous = poses.get(player);
        if (previous == null || !previous.sameAs(x, y, z, yaw, pitch)) {
            poses.put(player, new Pose(x, y, z, yaw, pitch, now));
            return false;
        }
        return timeoutMillis > 0 && now - previous.lastActive() >= timeoutMillis;
    }

    /** Marks the player active now (chat, command or interaction). */
    public void touch(UUID player, long now) {
        Pose p = poses.get(player);
        if (p != null) {
            poses.put(player, new Pose(p.x(), p.y(), p.z(), p.yaw(), p.pitch(), now));
        }
    }

    public void forget(UUID player) {
        poses.remove(player);
    }

    public void clear() {
        poses.clear();
    }
}
