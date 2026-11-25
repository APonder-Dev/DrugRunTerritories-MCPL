/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import dev.aponder.drugrun.territories.api.KillDecision;
import dev.aponder.drugrun.territories.bonus.StackingMode;
import dev.aponder.drugrun.territories.faction.FactionRank;
import dev.aponder.drugrun.territories.lootkey.KillPolicySettings;
import dev.aponder.drugrun.territories.storage.Dialect;
import dev.aponder.drugrun.territories.storage.StorageSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Turns {@code config.yml} into a {@link Settings} snapshot. Every value is range-checked and
 * contradictory combinations are rejected. All problems are collected, so the administrator sees
 * the full list at once.
 */
public final class SettingsLoader {

    /** Highest {@code config-version} this build understands. */
    public static final int CONFIG_VERSION = 1;

    /** Outcome of a load: either settings, or the list of problems. */
    public record Result(Settings settings, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final List<String> errors = new ArrayList<>();

    private SettingsLoader() {
    }

    /** Parses and validates {@code root} (the whole of config.yml). */
    public static Result load(ConfigurationSection root) {
        SettingsLoader loader = new SettingsLoader();
        Settings settings = loader.parse(root);
        return new Result(loader.errors.isEmpty() ? settings : null, List.copyOf(loader.errors));
    }

    private Settings parse(ConfigurationSection root) {
        int version = root.getInt("config-version", 0);
        if (version > CONFIG_VERSION) {
            errors.add("config-version " + version + " is newer than this plugin supports (" + CONFIG_VERSION + ")");
        }

        Settings.Integrations integrations = new Settings.Integrations(
                root.getBoolean("integrations.drugruncore.required", false),
                root.getBoolean("integrations.druglab.enabled", true),
                root.getBoolean("integrations.druglab.required-for-drug-bonuses", true),
                root.getBoolean("integrations.vault.enabled", true),
                root.getBoolean("integrations.placeholderapi.enabled", true),
                root.getBoolean("integrations.worldguard.enabled", true));

        String c = "territories.capture.";
        Settings.Capture capture = new Settings.Capture(
                Duration.ofSeconds(intIn(root, c + "duration-seconds", 600, 10, 86_400)),
                intIn(root, c + "minimum-faction-players", 2, 1, 100),
                enumOf(root, c + "contested-behavior", Settings.ContestedBehavior.class, Settings.ContestedBehavior.PAUSE),
                doubleIn(root, c + "decay-per-second", 1.0, 0.0, 100.0),
                root.getBoolean(c + "decay-when-abandoned", true),
                Duration.ofHours(intIn(root, c + "ownership-duration-hours", 24, 1, 24 * 30)),
                Duration.ofMinutes(intIn(root, c + "cooldown-minutes", 60, 0, 60 * 24 * 7)),
                intIn(root, c + "maximum-per-faction", 2, 1, 100),
                Duration.ofSeconds(intIn(root, c + "afk-timeout-seconds", 120, 0, 3_600)),
                root.getBoolean(c + "exclude-creative", true),
                root.getBoolean(c + "allow-challenges", true));
        if (capture.contestedBehavior() == Settings.ContestedBehavior.DECAY && capture.decayPerSecond() <= 0) {
            errors.add(c + "contested-behavior is DECAY but decay-per-second is 0; progress would never drain");
        }

        String b = "territories.bonuses.";
        StackingMode stacking = enumOf(root, b + "stacking", StackingMode.class, StackingMode.CAPPED_MULTIPLICATIVE);
        double hardCap = doubleIn(root, b + "hard-cap", 1.25, 1.0, 5.0);
        Settings.Bonuses bonuses = new Settings.Bonuses(
                stacking,
                hardCap,
                Set.copyOf(root.getStringList(b + "dealer.allowed-worlds")),
                enumOf(root, b + "dealer.minimum-rank", FactionRank.class, FactionRank.RECRUIT),
                doubleIn(root, b + "dealer.max-bonus-per-transaction", 5000.0, 0.0, 1.0e12));

        Settings.Income income = new Settings.Income(
                root.getBoolean("territories.income.pay-missed-periods", false),
                enumOf(root, "territories.income.recipient", Settings.IncomeRecipient.class, Settings.IncomeRecipient.LEADER));

        Settings.Protection protection = new Settings.Protection(
                root.getBoolean("territories.protection.prevent-arena-griefing", true),
                root.getBoolean("territories.protection.respect-factionsuuid", true),
                root.getBoolean("territories.protection.respect-worldguard", true));
        if (!protection.respectFactionsUuid()) {
            errors.add("territories.protection.respect-factionsuuid cannot be false: FactionsUUID is authoritative for claims");
        }

        Settings.Raiding raiding = new Settings.Raiding(
                root.getBoolean("raiding.protect-druglab-in-protected-claims", true),
                enumOf(root, "raiding.wilderness-druglab-explosions", Settings.WildernessExplosions.class, Settings.WildernessExplosions.ALLOW),
                root.getBoolean("raiding.fail-closed", true),
                enumOf(root, "raiding.plant-explosion-drops", Settings.ExplosionDrops.class, Settings.ExplosionDrops.NORMAL),
                doubleIn(root, "raiding.reduced-drop-fraction", 0.5, 0.0, 1.0));

        Settings.DrugLab druglab = new Settings.DrugLab(
                root.getBoolean("druglab.plants.check-full-footprint", true),
                root.getBoolean("druglab.plants.freeze-on-unauthorized-growth", true),
                root.getBoolean("druglab.plants.require-access-to-harvest", true),
                root.getBoolean("druglab.plants.require-access-to-fertilize", true),
                root.getBoolean("druglab.stations.require-container-access", true));

        Settings.Progression progression = parseProgression(root, capture);

        KillPolicySettings killPolicy = parseKillPolicy(root);

        String a = "announcements.";
        Settings.Announcements announcements = new Settings.Announcements(
                root.getBoolean(a + "capture-started", true),
                root.getBoolean(a + "capture-contested", true),
                root.getBoolean(a + "capture-completed", true),
                root.getBoolean(a + "territory-expired", true),
                root.getBoolean(a + "faction-raidable", true),
                root.getString(a + "sounds.capture-completed", "entity.player.levelup"),
                root.getString(a + "sounds.contested", "block.note_block.bass"),
                (float) doubleIn(root, a + "sounds.volume", 1.0, 0.0, 10.0),
                (float) doubleIn(root, a + "sounds.pitch", 1.0, 0.5, 2.0),
                root.getBoolean(a + "particles.enabled", true),
                root.getString(a + "particles.type", "HAPPY_VILLAGER"),
                intIn(root, a + "particles.count", 20, 0, 500),
                root.getBoolean(a + "bossbar.enabled", true),
                root.getBoolean(a + "actionbar.enabled", true));

        StorageSettings storage = parseStorage(root);

        Settings.Performance performance = new Settings.Performance(
                intIn(root, "performance.async-threads", 2, 1, 16),
                intIn(root, "performance.async-queue-size", 2000, 16, 100_000),
                Duration.ofSeconds(intIn(root, "performance.progress-save-interval-seconds", 15, 1, 600)));

        return new Settings(
                root.getBoolean("general.debug", false),
                integrations,
                root.getBoolean("territories.enabled", true),
                intIn(root, "territories.tick-interval-ticks", 20, 5, 200),
                capture, bonuses, income, protection, raiding, druglab, progression, killPolicy,
                announcements, storage, performance);
    }

    private Settings.Progression parseProgression(ConfigurationSection root, Settings.Capture capture) {
        String p = "progression.";
        List<Settings.Level> levels = new ArrayList<>();
        List<Map<?, ?>> raw = root.getMapList(p + "levels");
        long previousXp = -1;
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            long xp = toLong(entry.get("xp"), -1);
            int slots = (int) toLong(entry.get("extra-territory-slots"), 0);
            if (xp < 0) {
                errors.add(p + "levels[" + i + "].xp must be a number >= 0");
                continue;
            }
            if (slots < 0) {
                errors.add(p + "levels[" + i + "].extra-territory-slots cannot be negative");
            }
            if (xp <= previousXp) {
                errors.add(p + "levels must have strictly increasing xp (level " + (i + 1) + ")");
            }
            previousXp = xp;
            levels.add(new Settings.Level(i + 1, xp, Math.max(0, slots)));
        }
        if (levels.isEmpty()) {
            levels.add(new Settings.Level(1, 0, 0));
        } else if (levels.getFirst().xpRequired() != 0) {
            errors.add(p + "levels: the first level must require 0 xp");
        }
        int absoluteMax = intIn(root, p + "absolute-max-territories", 3, 1, 100);
        if (absoluteMax < capture.maximumPerFaction()) {
            errors.add(p + "absolute-max-territories (" + absoluteMax + ") is below territories.capture.maximum-per-faction ("
                    + capture.maximumPerFaction() + ")");
        }
        return new Settings.Progression(
                root.getBoolean(p + "enabled", true),
                longIn(root, p + "xp.territory-capture", 500),
                longIn(root, p + "xp.territory-defense", 250),
                longIn(root, p + "xp.raid-victory", 300),
                intIn(root, p + "xp.production-milestone-harvests", 50, 0, 1_000_000),
                longIn(root, p + "xp.production-milestone-xp", 100),
                longIn(root, p + "xp.control-per-minute", 1),
                intIn(root, p + "xp.sale-milestone-items", 500, 0, 100_000_000),
                longIn(root, p + "xp.sale-milestone-xp", 100),
                intIn(root, p + "xp.processing-milestone-jobs", 25, 0, 1_000_000),
                longIn(root, p + "xp.processing-milestone-xp", 50),
                longIn(root, p + "reputation.territory-capture", 25),
                longIn(root, p + "reputation.territory-defense", 10),
                longIn(root, p + "reputation.raid-victory", 15),
                Duration.ofHours(intIn(root, p + "raid-victory-cooldown-hours", 6, 0, 24 * 30)),
                List.copyOf(levels),
                absoluteMax);
    }

    private KillPolicySettings parseKillPolicy(ConfigurationSection root) {
        String k = "loot-keys.faction-policy.";
        Map<String, KillDecision.Outcome> overrides = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection(k + "territory-overrides");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String value = String.valueOf(section.get(id)).toUpperCase(Locale.ROOT);
                if (value.equals("ALLOW") || value.equals("DENY")) {
                    overrides.put(id.toLowerCase(Locale.ROOT), KillDecision.Outcome.valueOf(value));
                } else {
                    errors.add(k + "territory-overrides." + id + " must be ALLOW or DENY");
                }
            }
        }
        KillPolicySettings policy = new KillPolicySettings(
                root.getBoolean(k + "prevent-same-faction-kills", true),
                root.getBoolean(k + "prevent-allied-faction-kills", true),
                root.getBoolean(k + "treat-truce-as-ally", true),
                root.getBoolean(k + "allow-enemy-faction-kills", true),
                root.getBoolean(k + "allow-neutral-kills", true),
                root.getBoolean(k + "allow-wilderness-pvp", true),
                root.getBoolean(k + "allow-warzone-pvp", true),
                root.getBoolean(k + "deny-in-safezones", true),
                Map.copyOf(overrides));
        if (!policy.preventSameFaction()) {
            errors.add(k + "prevent-same-faction-kills cannot be false: it would allow Loot Key farming inside one faction");
        }
        return policy;
    }

    private StorageSettings parseStorage(ConfigurationSection root) {
        String s = "storage.";
        Dialect dialect = enumOf(root, s + "type", Dialect.class, Dialect.SQLITE);
        String prefix = root.getString(s + "table-prefix", "drt_");
        if (!prefix.matches("[A-Za-z0-9_]{0,16}")) {
            errors.add(s + "table-prefix may only contain letters, digits and _ (max 16)");
            prefix = "drt_";
        }
        String file = root.getString(s + "sqlite-file", "territories.db");
        if (file.contains("..") || file.contains("/") || file.contains("\\")) {
            errors.add(s + "sqlite-file must be a plain file name inside the plugin folder");
        }
        String database = root.getString(s + "database", "drugrun");
        if (dialect != Dialect.SQLITE && !database.matches("[A-Za-z0-9_\\-]{1,64}")) {
            errors.add(s + "database contains invalid characters");
        }
        return new StorageSettings(
                dialect,
                file,
                root.getString(s + "host", "localhost"),
                intIn(root, s + "port", 3306, 1, 65_535),
                database,
                root.getString(s + "username", "drugrun"),
                root.getString(s + "password", ""),
                prefix,
                intIn(root, s + "pool-size", 4, 1, 64),
                Duration.ofMillis(intIn(root, s + "connection-timeout-ms", 10_000, 250, 120_000)),
                intIn(root, s + "query-timeout-seconds", 10, 1, 300));
    }

    // ---- helpers -------------------------------------------------------------------------

    private int intIn(ConfigurationSection root, String path, int def, int min, int max) {
        Object raw = root.get(path, def);
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            errors.add(path + " must be a whole number (got " + raw + ")");
            return def;
        }
        int v = n.intValue();
        if (v < min || v > max) {
            errors.add(path + " must be between " + min + " and " + max + " (got " + v + ")");
            return def;
        }
        return v;
    }

    private long longIn(ConfigurationSection root, String path, long def) {
        Object raw = root.get(path, def);
        if (!(raw instanceof Number n) || n.longValue() < 0) {
            errors.add(path + " must be a whole number >= 0 (got " + raw + ")");
            return def;
        }
        return n.longValue();
    }

    private double doubleIn(ConfigurationSection root, String path, double def, double min, double max) {
        Object raw = root.get(path, def);
        if (!(raw instanceof Number n) || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
            errors.add(path + " must be a number (got " + raw + ")");
            return def;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            errors.add(path + " must be between " + min + " and " + max + " (got " + v + ")");
            return def;
        }
        return v;
    }

    private <E extends Enum<E>> E enumOf(ConfigurationSection root, String path, Class<E> type, E def) {
        String raw = root.getString(path);
        if (raw == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Set<String> names = new LinkedHashSet<>();
            for (E constant : type.getEnumConstants()) {
                names.add(constant.name());
            }
            errors.add(path + " must be one of " + names + " (got " + raw + ")");
            return def;
        }
    }

    private static long toLong(Object value, long def) {
        return value instanceof Number n ? n.longValue() : def;
    }
}
