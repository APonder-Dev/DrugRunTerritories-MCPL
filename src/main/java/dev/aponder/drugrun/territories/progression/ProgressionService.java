/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

import dev.aponder.drugrun.territories.config.Settings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Faction XP, reputation, levels and statistics.
 *
 * <p>Reads come from an in-memory copy (main thread only, cheap enough for placeholders); every
 * change updates that copy and is written to storage as an increment in the background. When
 * progression is disabled in config, statistics are still counted but no XP or reputation is
 * granted.
 */
public final class ProgressionService {

    private final ProgressionRepository repository;
    private final Logger logger;
    private final Map<String, FactionProgress> cache = new HashMap<>();
    /** "attacker|defender" -> epoch millis of the last counted raid victory */
    private final Map<String, Long> lastRaidVictory = new HashMap<>();
    private Settings.Progression settings;
    private int baseTerritoryLimit;

    public ProgressionService(ProgressionRepository repository, Settings.Progression settings, int baseTerritoryLimit, Logger logger) {
        this.repository = repository;
        this.settings = settings;
        this.baseTerritoryLimit = baseTerritoryLimit;
        this.logger = logger;
    }

    /** Replaces the in-memory copy; called once at startup with stored data. */
    public void load(Map<String, FactionProgress> stored, List<ProgressionRepository.RaidVictoryRecord> recentRaids) {
        cache.clear();
        cache.putAll(stored);
        lastRaidVictory.clear();
        for (ProgressionRepository.RaidVictoryRecord r : recentRaids) {
            lastRaidVictory.merge(r.attacker() + "|" + r.defender(), r.occurredAt(), Math::max);
        }
    }

    /** Applies new settings after a reload. */
    public void reconfigure(Settings.Progression newSettings, int newBaseTerritoryLimit) {
        this.settings = newSettings;
        this.baseTerritoryLimit = newBaseTerritoryLimit;
    }

    public Settings.Progression settings() {
        return settings;
    }

    public FactionProgress get(String factionId) {
        return cache.getOrDefault(factionId, FactionProgress.empty(factionId));
    }

    public Settings.Level level(String factionId) {
        return settings.levelFor(get(factionId).xp());
    }

    /** @return how many territories the faction may hold, including level unlocks and the absolute ceiling */
    public int territoryLimit(String factionId) {
        int unlocked = settings.enabled() ? settings.extraSlotsFor(get(factionId).xp()) : 0;
        return Math.min(baseTerritoryLimit + unlocked, settings.absoluteMaxTerritories());
    }

    /** Adds a change, stripping XP and reputation when progression is disabled. */
    public void add(String factionId, ProgressDelta delta) {
        ProgressDelta effective = settings.enabled() ? delta
                : new ProgressDelta(0, 0, delta.captures(), delta.defenses(), delta.raidVictories(), delta.controlSeconds(),
                delta.harvests(), delta.income(), delta.itemsSold(), delta.salesValue(), delta.jobsProcessed());
        if (effective.empty()) {
            return;
        }
        cache.put(factionId, get(factionId).plus(effective));
        repository.add(factionId, effective).exceptionally(t -> {
            logger.log(Level.SEVERE, "Could not save progression for faction " + factionId + "; the in-memory value is kept", t);
            return null;
        });
    }

    /**
     * Records a mature harvest and grants milestone XP when the faction's harvest count reaches a
     * multiple of the configured milestone.
     */
    public void recordHarvest(String factionId) {
        long before = get(factionId).harvests();
        int milestone = settings.productionMilestoneHarvests();
        long xp = milestone > 0 && (before + 1) % milestone == 0 ? settings.productionMilestoneXp() : 0;
        add(factionId, ProgressDelta.harvest(xp));
    }

    /**
     * Records a paid dealer sale and grants milestone XP each time the faction's total items sold
     * crosses a multiple of the configured milestone.
     */
    public void recordSale(String factionId, int items, double value) {
        if (items <= 0 || !(value >= 0)) {
            return;
        }
        long before = get(factionId).itemsSold();
        int milestone = settings.saleMilestoneItems();
        long crossed = milestone > 0 ? (before + items) / milestone - before / milestone : 0;
        add(factionId, ProgressDelta.sale(items, value, crossed * settings.saleMilestoneXp()));
    }

    /** Records a collected processing job and grants milestone XP every N jobs. */
    public void recordProcessing(String factionId) {
        long before = get(factionId).jobsProcessed();
        int milestone = settings.processingMilestoneJobs();
        long xp = milestone > 0 && (before + 1) % milestone == 0 ? settings.processingMilestoneXp() : 0;
        add(factionId, ProgressDelta.processed(xp));
    }

    /**
     * Counts a raid victory unless the same attacker already got one against the same defender
     * within the cooldown.
     *
     * @return whether it was counted
     */
    public boolean tryRaidVictory(String attacker, String defender, long now) {
        String key = attacker + "|" + defender;
        Long last = lastRaidVictory.get(key);
        if (last != null && now - last < settings.raidVictoryCooldown().toMillis()) {
            return false;
        }
        lastRaidVictory.put(key, now);
        repository.recordRaidVictory(new ProgressionRepository.RaidVictoryRecord(attacker, defender, now)).exceptionally(t -> {
            logger.log(Level.WARNING, "Could not store raid victory " + key, t);
            return null;
        });
        add(attacker, ProgressDelta.raidVictory(settings.raidVictoryXp(), settings.raidVictoryReputation()));
        return true;
    }

    /** Clears a faction's progression (admin command or disband cleanup). */
    public void reset(String factionId) {
        cache.remove(factionId);
        lastRaidVictory.keySet().removeIf(k -> k.startsWith(factionId + "|"));
        repository.delete(factionId).exceptionally(t -> {
            logger.log(Level.SEVERE, "Could not delete progression for faction " + factionId, t);
            return null;
        });
    }

    /** @return the top {@code limit} factions by {@code metric}, highest first */
    public List<FactionProgress> top(ToLongFunction<FactionProgress> metric, int limit) {
        List<FactionProgress> all = new ArrayList<>(cache.values());
        all.sort(Comparator.comparingLong(metric).reversed().thenComparing(FactionProgress::factionId));
        return all.subList(0, Math.min(limit, all.size()));
    }
}
