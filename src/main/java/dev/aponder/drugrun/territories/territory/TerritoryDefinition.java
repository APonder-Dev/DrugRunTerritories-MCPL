/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Static definition of one territory, from {@code territories.yml}. Immutable; editing commands
 * build a new definition and save it.
 *
 * @param id lower-case unique id, used in commands, storage and placeholders
 * @param displayName MiniMessage display name
 * @param allowedFactions faction ids or tags allowed to capture; empty means every faction
 * @param schedule daily capture windows; empty means always open
 * @param bonuses multiplier per bonus type for the controlling faction
 * @param income recurring payout, or {@code null} for none
 */
public record TerritoryDefinition(
        String id,
        String displayName,
        TerritoryType type,
        boolean enabled,
        Region region,
        CaptureZone captureZone,
        CaptureOverrides overrides,
        Set<String> allowedFactions,
        List<TimeWindow> schedule,
        Protection protection,
        PvpRule pvp,
        Map<BonusType, Double> bonuses,
        Income income,
        Rewards rewards) {

    /** Arena protection mode. */
    public enum Protection {
        /** Blocks inside the region cannot be broken, placed, exploded, flooded, pushed or burned. */
        ARENA,
        /** DrugRunTerritories does not protect the region; other plugins may. */
        NONE
    }

    /** PvP rule inside the region. */
    public enum PvpRule {
        /** Leave PvP to FactionsUUID and WorldGuard. */
        DEFAULT,
        /** Cancel player-versus-player damage inside the region. */
        DENY
    }

    /** Per-territory overrides of the global capture settings; {@code null} fields use the default. */
    public record CaptureOverrides(Duration duration, Integer minimumPlayers, Duration ownership, Duration cooldown) {
        public static final CaptureOverrides NONE = new CaptureOverrides(null, null, null, null);
    }

    /** Recurring faction income. */
    public record Income(double amount, Duration interval, int maxPayoutsPerOwnership) {
        public Income {
            if (amount < 0 || interval.isNegative() || interval.isZero() || maxPayoutsPerOwnership < 0) {
                throw new IllegalArgumentException("Invalid income settings");
            }
        }
    }

    /** One-off rewards to the capturing faction. {@code money} is paid like income. */
    public record Rewards(long xp, long reputation, double money) {
        public static final Rewards NONE = new Rewards(0, 0, 0);
    }

    public TerritoryDefinition {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-z0-9_\\-]{1,32}")) {
            throw new IllegalArgumentException("Territory id must be 1-32 characters of a-z, 0-9, _ or -");
        }
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(captureZone, "captureZone");
        allowedFactions = Set.copyOf(allowedFactions);
        schedule = List.copyOf(schedule);
        bonuses = Map.copyOf(bonuses);
        overrides = overrides == null ? CaptureOverrides.NONE : overrides;
        rewards = rewards == null ? Rewards.NONE : rewards;
    }

    /** @return the world this territory is in */
    public String world() {
        return region.world();
    }

    /** @return whether the capture zone centre lies inside the region */
    public boolean captureInsideRegion() {
        return region.contains(region.world(), (int) Math.floor(captureZone.x()), (int) Math.floor(captureZone.y()),
                (int) Math.floor(captureZone.z()));
    }

    /** @return whether capturing is allowed at {@code time} */
    public boolean scheduleOpen(LocalTime time) {
        return schedule.isEmpty() || schedule.stream().anyMatch(w -> w.contains(time));
    }

    /** @return whether a faction with this id and tag may capture */
    public boolean allows(String factionId, String factionTag) {
        if (allowedFactions.isEmpty()) {
            return true;
        }
        return allowedFactions.contains(factionId) || allowedFactions.contains(factionTag.toLowerCase(Locale.ROOT));
    }

    /** @return capture rules after applying this territory's overrides to the global defaults */
    public CaptureRules rules(Settings.Capture defaults) {
        return new CaptureRules(
                overrides.duration() != null ? overrides.duration() : defaults.duration(),
                overrides.minimumPlayers() != null ? overrides.minimumPlayers() : defaults.minimumPlayers(),
                defaults.contestedBehavior(),
                defaults.decayPerSecond(),
                defaults.decayWhenAbandoned(),
                overrides.ownership() != null ? overrides.ownership() : defaults.ownershipDuration(),
                overrides.cooldown() != null ? overrides.cooldown() : defaults.cooldown(),
                defaults.allowChallenges());
    }

    /** @return a copy with a different region */
    public TerritoryDefinition withRegion(Region newRegion) {
        return new TerritoryDefinition(id, displayName, type, enabled, newRegion, captureZone, overrides, allowedFactions,
                schedule, protection, pvp, bonuses, income, rewards);
    }

    /** @return a copy with a different capture zone */
    public TerritoryDefinition withCaptureZone(CaptureZone zone) {
        return new TerritoryDefinition(id, displayName, type, enabled, region, zone, overrides, allowedFactions,
                schedule, protection, pvp, bonuses, income, rewards);
    }

    /** @return a copy with {@code enabled} changed */
    public TerritoryDefinition withEnabled(boolean value) {
        return new TerritoryDefinition(id, displayName, type, value, region, captureZone, overrides, allowedFactions,
                schedule, protection, pvp, bonuses, income, rewards);
    }
}
