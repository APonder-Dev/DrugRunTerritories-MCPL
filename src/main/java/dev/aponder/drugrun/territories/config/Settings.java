/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import dev.aponder.drugrun.territories.bonus.StackingMode;
import dev.aponder.drugrun.territories.faction.FactionRank;
import dev.aponder.drugrun.territories.lootkey.KillPolicySettings;
import dev.aponder.drugrun.territories.storage.StorageSettings;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Immutable, validated snapshot of {@code config.yml}. Build it with {@link SettingsLoader}.
 * A reload produces a new instance; nothing mutates an existing one, so a snapshot can be read
 * from any thread.
 */
public record Settings(
        boolean debug,
        Integrations integrations,
        boolean territoriesEnabled,
        int tickIntervalTicks,
        Capture capture,
        Bonuses bonuses,
        Income income,
        Protection protection,
        Raiding raiding,
        DrugLab druglab,
        Progression progression,
        KillPolicySettings killPolicy,
        Announcements announcements,
        StorageSettings storage,
        Performance performance) {

    /** Which integrations are switched on and which are mandatory. */
    public record Integrations(
            boolean drugRunCoreRequired,
            boolean drugLabEnabled,
            boolean drugLabRequiredForBonuses,
            boolean vault,
            boolean placeholderApi,
            boolean worldGuard) {
    }

    /** What happens to capture progress while the zone is contested. */
    public enum ContestedBehavior { PAUSE, DECAY }

    /** Global capture defaults; each territory may override some of them. */
    public record Capture(
            Duration duration,
            int minimumPlayers,
            ContestedBehavior contestedBehavior,
            double decayPerSecond,
            boolean decayWhenAbandoned,
            Duration ownershipDuration,
            Duration cooldown,
            int maximumPerFaction,
            Duration afkTimeout,
            boolean excludeCreative,
            boolean allowChallenges) {
    }

    /** Bonus stacking and dealer-sale rules. */
    public record Bonuses(
            StackingMode stacking,
            double hardCap,
            Set<String> dealerAllowedWorlds,
            FactionRank dealerMinimumRank,
            double dealerMaxBonusPerTransaction) {
    }

    /** Who receives recurring territory income. */
    public enum IncomeRecipient { LEADER, ONLINE_MEMBERS }

    /** Recurring income behaviour. */
    public record Income(boolean payMissedPeriods, IncomeRecipient recipient) {
    }

    /** Arena protection switches. */
    public record Protection(boolean preventArenaGriefing, boolean respectFactionsUuid, boolean respectWorldGuard) {
    }

    /** Wilderness rule for DrugLab blocks caught in explosions. */
    public enum WildernessExplosions { ALLOW, PREVENT }

    /** What an explosion that destroys a DrugLab plant drops. */
    public enum ExplosionDrops { NORMAL, REDUCED, NONE }

    /** Raid coordination switches. */
    public record Raiding(boolean protectDrugLabInProtectedClaims, WildernessExplosions wildernessDrugLab, boolean failClosed,
                          ExplosionDrops plantExplosionDrops, double reducedDropFraction) {
    }

    /** DrugLab authorization switches. */
    public record DrugLab(
            boolean checkFullFootprint,
            boolean freezeOnUnauthorizedGrowth,
            boolean requireAccessToHarvest,
            boolean requireAccessToFertilize,
            boolean requireStationContainerAccess) {
    }

    /** One progression level. */
    public record Level(int number, long xpRequired, int extraTerritorySlots) {
    }

    /** Faction XP, reputation and levels. */
    public record Progression(
            boolean enabled,
            long captureXp,
            long defenseXp,
            long raidVictoryXp,
            int productionMilestoneHarvests,
            long productionMilestoneXp,
            long controlXpPerMinute,
            int saleMilestoneItems,
            long saleMilestoneXp,
            int processingMilestoneJobs,
            long processingMilestoneXp,
            long captureReputation,
            long defenseReputation,
            long raidVictoryReputation,
            Duration raidVictoryCooldown,
            List<Level> levels,
            int absoluteMaxTerritories) {

        /** @return the highest level whose XP requirement {@code xp} meets */
        public Level levelFor(long xp) {
            Level best = levels.getFirst();
            for (Level level : levels) {
                if (xp >= level.xpRequired()) {
                    best = level;
                }
            }
            return best;
        }

        /** @return extra territory slots unlocked by every level up to and including the one {@code xp} reaches */
        public int extraSlotsFor(long xp) {
            int slots = 0;
            for (Level level : levels) {
                if (xp >= level.xpRequired()) {
                    slots += level.extraTerritorySlots();
                }
            }
            return slots;
        }
    }

    /** Player-facing feedback switches. */
    public record Announcements(
            boolean captureStarted,
            boolean captureContested,
            boolean captureCompleted,
            boolean territoryExpired,
            boolean factionRaidable,
            String completedSound,
            String contestedSound,
            float volume,
            float pitch,
            boolean particles,
            String particleType,
            int particleCount,
            boolean bossBar,
            boolean actionBar) {
    }

    /** Thread-pool and save-interval tuning. */
    public record Performance(int asyncThreads, int asyncQueueSize, Duration progressSaveInterval) {
    }
}
