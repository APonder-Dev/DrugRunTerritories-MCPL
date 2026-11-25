/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.bonus.BonusType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reads and writes {@code territories.yml}. Problems are collected rather than thrown. */
public final class TerritoryDefinitionCodec {

    /** Parsed definitions, or the problems that stopped them loading. */
    public record Result(List<TerritoryDefinition> definitions, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private TerritoryDefinitionCodec() {
    }

    /** Parses every entry under {@code territories}, then checks for overlapping regions. */
    public static Result read(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        List<TerritoryDefinition> defs = new ArrayList<>();
        ConfigurationSection section = root.getConfigurationSection("territories");
        if (section == null) {
            return new Result(List.of(), List.of());
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection t = section.getConfigurationSection(key);
            String where = "territories." + key;
            if (t == null) {
                errors.add(where + " must be a section");
                continue;
            }
            try {
                defs.add(parse(key.toLowerCase(Locale.ROOT), t, where, errors));
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
            }
        }
        if (errors.isEmpty()) {
            try {
                TerritoryRegistry registry = new TerritoryRegistry(defs);
                for (TerritoryDefinition def : registry.enabled()) {
                    registry.overlapping(def).ifPresent(other -> {
                        String pair = def.id().compareTo(other.id()) < 0 ? def.id() + " and " + other.id() : other.id() + " and " + def.id();
                        String msg = "territories " + pair + " overlap; enabled regions must not overlap";
                        if (!errors.contains(msg)) {
                            errors.add(msg);
                        }
                    });
                }
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        return new Result(List.copyOf(defs), List.copyOf(errors));
    }

    private static TerritoryDefinition parse(String id, ConfigurationSection t, String where, List<String> errors) {
        String world = t.getString("world");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required");
        }
        int[] min = vector(t, "region.min", where);
        int[] max = vector(t, "region.max", where);
        Region region = Region.of(world, min[0], min[1], min[2], max[0], max[1], max[2]);
        List<?> center = t.getList("capture.center");
        if (center == null || center.size() != 3 || !center.stream().allMatch(Number.class::isInstance)) {
            throw new IllegalArgumentException("capture.center must be [x, y, z]");
        }
        double radius = t.getDouble("capture.radius", 8);
        CaptureZone zone = new CaptureZone(((Number) center.get(0)).doubleValue(), ((Number) center.get(1)).doubleValue(),
                ((Number) center.get(2)).doubleValue(), radius);

        TerritoryDefinition.CaptureOverrides overrides = new TerritoryDefinition.CaptureOverrides(
                t.isInt("capture.duration-seconds") ? Duration.ofSeconds(positive(t, "capture.duration-seconds", where)) : null,
                t.isInt("capture.minimum-faction-players") ? positive(t, "capture.minimum-faction-players", where) : null,
                t.isInt("capture.ownership-duration-hours") ? Duration.ofHours(positive(t, "capture.ownership-duration-hours", where)) : null,
                t.isInt("capture.cooldown-minutes") ? Duration.ofMinutes(Math.max(0, t.getInt("capture.cooldown-minutes"))) : null);

        Set<String> allowed = new LinkedHashSet<>();
        for (String f : t.getStringList("allowed-factions")) {
            allowed.add(f.toLowerCase(Locale.ROOT));
        }
        List<TimeWindow> schedule = new ArrayList<>();
        for (String w : t.getStringList("schedule")) {
            schedule.add(TimeWindow.parse(w));
        }
        Map<BonusType, Double> bonuses = new EnumMap<>(BonusType.class);
        ConfigurationSection b = t.getConfigurationSection("bonuses");
        if (b != null) {
            for (String k : b.getKeys(false)) {
                BonusType type;
                try {
                    type = BonusType.valueOf(k.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add(where + ".bonuses." + k + " is not a bonus type (DEALER_SELL, PROCESSING_SPEED, CROP_GROWTH)");
                    continue;
                }
                double v = b.getDouble(k, 1.0);
                if (!(v >= 1.0 && v <= 3.0)) {
                    errors.add(where + ".bonuses." + k + " must be between 1.0 and 3.0");
                    continue;
                }
                bonuses.put(type, v);
            }
        }
        TerritoryDefinition.Income income = null;
        if (t.isConfigurationSection("income")) {
            double amount = t.getDouble("income.amount", 0);
            int minutes = t.getInt("income.interval-minutes", 60);
            int maxPayouts = t.getInt("income.max-payouts-per-ownership", 0);
            if (amount < 0 || minutes < 1 || maxPayouts < 0) {
                errors.add(where + ".income: amount >= 0, interval-minutes >= 1 and max-payouts-per-ownership >= 0 required");
            } else if (amount > 0) {
                income = new TerritoryDefinition.Income(amount, Duration.ofMinutes(minutes), maxPayouts);
            }
        }
        TerritoryDefinition.Rewards rewards = new TerritoryDefinition.Rewards(
                Math.max(0, t.getLong("rewards.xp", 0)),
                Math.max(0, t.getLong("rewards.reputation", 0)),
                Math.max(0, t.getDouble("rewards.money", 0)));

        TerritoryDefinition def = new TerritoryDefinition(
                id,
                t.getString("display-name", id),
                enumOf(TerritoryType.class, t.getString("type", "CUSTOM"), where + ".type"),
                t.getBoolean("enabled", true),
                region,
                zone,
                overrides,
                allowed,
                schedule,
                enumOf(TerritoryDefinition.Protection.class, t.getString("protection", "ARENA"), where + ".protection"),
                enumOf(TerritoryDefinition.PvpRule.class, t.getString("pvp", "DEFAULT"), where + ".pvp"),
                bonuses,
                income,
                rewards);
        if (!def.captureInsideRegion()) {
            errors.add(where + ": capture.center must lie inside the region");
        }
        return def;
    }

    /** Writes one definition into {@code root} under {@code territories.<id>}, replacing any previous entry. */
    public static void write(YamlConfiguration root, TerritoryDefinition def) {
        String p = "territories." + def.id();
        root.set(p, null);
        root.set(p + ".display-name", def.displayName());
        root.set(p + ".type", def.type().name());
        root.set(p + ".enabled", def.enabled());
        root.set(p + ".world", def.world());
        Region r = def.region();
        root.set(p + ".region.min", List.of(r.minX(), r.minY(), r.minZ()));
        root.set(p + ".region.max", List.of(r.maxX(), r.maxY(), r.maxZ()));
        CaptureZone z = def.captureZone();
        root.set(p + ".capture.center", List.of(z.x(), z.y(), z.z()));
        root.set(p + ".capture.radius", z.radius());
        TerritoryDefinition.CaptureOverrides o = def.overrides();
        if (o.duration() != null) {
            root.set(p + ".capture.duration-seconds", (int) o.duration().toSeconds());
        }
        if (o.minimumPlayers() != null) {
            root.set(p + ".capture.minimum-faction-players", o.minimumPlayers());
        }
        if (o.ownership() != null) {
            root.set(p + ".capture.ownership-duration-hours", (int) o.ownership().toHours());
        }
        if (o.cooldown() != null) {
            root.set(p + ".capture.cooldown-minutes", (int) o.cooldown().toMinutes());
        }
        root.set(p + ".allowed-factions", List.copyOf(def.allowedFactions()));
        root.set(p + ".schedule", def.schedule().stream().map(TimeWindow::toString).toList());
        root.set(p + ".protection", def.protection().name());
        root.set(p + ".pvp", def.pvp().name());
        Map<String, Object> bonuses = new LinkedHashMap<>();
        def.bonuses().forEach((k, v) -> bonuses.put(k.name(), v));
        root.createSection(p + ".bonuses", bonuses);
        if (def.income() != null) {
            root.set(p + ".income.amount", def.income().amount());
            root.set(p + ".income.interval-minutes", (int) def.income().interval().toMinutes());
            root.set(p + ".income.max-payouts-per-ownership", def.income().maxPayoutsPerOwnership());
        }
        root.set(p + ".rewards.xp", def.rewards().xp());
        root.set(p + ".rewards.reputation", def.rewards().reputation());
        root.set(p + ".rewards.money", def.rewards().money());
    }

    private static int[] vector(ConfigurationSection t, String path, String where) {
        List<?> list = t.getList(path);
        if (list == null || list.size() != 3 || !list.stream().allMatch(Number.class::isInstance)) {
            throw new IllegalArgumentException(path + " must be [x, y, z]");
        }
        return new int[] {((Number) list.get(0)).intValue(), ((Number) list.get(1)).intValue(), ((Number) list.get(2)).intValue()};
    }

    private static int positive(ConfigurationSection t, String path, String where) {
        int v = t.getInt(path);
        if (v < 1) {
            throw new IllegalArgumentException(path + " must be at least 1");
        }
        return v;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String where) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + " has unknown value " + raw);
        }
    }
}
