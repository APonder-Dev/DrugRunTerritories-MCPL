/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.command;

import dev.aponder.drugrun.territories.audit.AuditLog;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.economy.PayoutRepository;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.FactionProgress;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.territory.CaptureZone;
import dev.aponder.drugrun.territories.territory.Region;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryEditor;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import dev.aponder.drugrun.territories.territory.TerritoryType;
import dev.aponder.drugrun.territories.ui.FactionNames;
import dev.aponder.drugrun.territories.ui.Messages;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /drugrun} with player and administrator sub-commands. Every sub-command checks its own
 * permission and validates input; every administrative change is audited. The root label is
 * {@code drugrun} with no aliases, so nothing clashes with FactionsUUID's {@code /f}.
 */
public final class DrugRunCommand implements BasicCommand {

    /** Reloads config and definitions; returns problems (empty on success). */
    @FunctionalInterface
    public interface Reloader {
        List<String> reload();
    }

    /** Status lines for {@code /drugrun admin debug}. */
    @FunctionalInterface
    public interface Diagnostics {
        List<String> lines();
    }

    private static final String P = "drugrun.territories.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> METRICS = List.of("xp", "reputation", "captures", "income");

    private final TerritoryService territories;
    private final TerritoryEditor editor;
    private final ProgressionService progression;
    private final FactionProvider factions;
    private final FactionNames names;
    private final Messages messages;
    private final AuditLog audit;
    private final PayoutRepository payouts;
    private final Reloader reloader;
    private final Diagnostics diagnostics;
    private final Executor mainThread;
    private final Supplier<Settings> settings;
    private final Logger logger;

    public DrugRunCommand(TerritoryService territories, TerritoryEditor editor, ProgressionService progression,
                          FactionProvider factions, FactionNames names, Messages messages, AuditLog audit,
                          PayoutRepository payouts, Reloader reloader, Diagnostics diagnostics, Executor mainThread,
                          Supplier<Settings> settings, Logger logger) {
        this.territories = territories;
        this.editor = editor;
        this.progression = progression;
        this.factions = factions;
        this.names = names;
        this.messages = messages;
        this.audit = audit;
        this.payouts = payouts;
        this.reloader = reloader;
        this.diagnostics = diagnostics;
        this.mainThread = mainThread;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        try {
            dispatch(sender, args);
        } catch (FactionLookupException e) {
            messages.send(sender, "general.factions-unavailable");
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Command failed: /drugrun " + String.join(" ", args), e);
            messages.send(sender, "general.error");
        }
    }

    private void dispatch(CommandSender sender, String[] args) {
        String a0 = arg(args, 0);
        String a1 = arg(args, 1);
        switch (a0) {
            case "", "help" -> help(sender);
            case "territories" -> {
                if (perm(sender, "command.territories")) {
                    list(sender);
                }
            }
            case "territory" -> {
                switch (a1) {
                    case "info" -> {
                        if (perm(sender, "command.info")) {
                            info(sender, arg(args, 2));
                        }
                    }
                    case "map" -> {
                        if (perm(sender, "command.map")) {
                            map(sender);
                        }
                    }
                    case "leaderboard" -> {
                        if (perm(sender, "command.leaderboard")) {
                            leaderboard(sender, arg(args, 2));
                        }
                    }
                    default -> usage(sender, "/drugrun territory <info|map|leaderboard>");
                }
            }
            case "faction" -> {
                switch (a1) {
                    case "stats" -> {
                        if (perm(sender, "command.faction.stats")) {
                            factionStats(sender);
                        }
                    }
                    case "progression" -> {
                        if (perm(sender, "command.faction.progression")) {
                            factionProgression(sender);
                        }
                    }
                    case "upgrades" -> {
                        if (perm(sender, "command.faction.upgrades")) {
                            factionUpgrades(sender);
                        }
                    }
                    default -> usage(sender, "/drugrun faction <stats|progression|upgrades>");
                }
            }
            case "admin" -> admin(sender, args);
            default -> messages.send(sender, "general.unknown-command");
        }
    }

    // ---- player commands ----------------------------------------------------------------------

    private void help(CommandSender sender) {
        if (!perm(sender, "command.help")) {
            return;
        }
        messages.send(sender, "help.player");
        if (sender.hasPermission(P + "admin")) {
            messages.send(sender, "help.admin");
        }
    }

    private void list(CommandSender sender) {
        List<TerritoryDefinition> all = territories.registry().enabled();
        if (all.isEmpty()) {
            messages.send(sender, "list.empty");
            return;
        }
        messages.send(sender, "list.header", Messages.text("count", all.size()));
        for (TerritoryDefinition def : all) {
            TerritoryState s = state(def);
            messages.send(sender, "list.entry", Messages.mini("territory", def.displayName()), Messages.text("id", def.id()),
                    Messages.text("status", s.state().name()), Messages.text("owner", names.name(s.ownerFactionId())));
        }
    }

    private void info(CommandSender sender, String id) {
        Optional<TerritoryDefinition> def;
        if (!id.isEmpty()) {
            def = territories.registry().get(id);
            if (def.isEmpty()) {
                messages.send(sender, "general.unknown-territory", Messages.text("id", id));
                return;
            }
        } else if (sender instanceof Player player) {
            Location l = player.getLocation();
            def = territories.registry().at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (def.isEmpty()) {
                messages.send(sender, "info.not-in-territory");
                return;
            }
        } else {
            usage(sender, "/drugrun territory info <id>");
            return;
        }
        TerritoryDefinition d = def.get();
        TerritoryState s = state(d);
        long now = System.currentTimeMillis();
        double duration = d.rules(settings.get().capture()).durationSeconds();
        StringBuilder bonuses = new StringBuilder();
        d.bonuses().forEach((type, value) -> bonuses.append(bonuses.isEmpty() ? "" : ", ").append(type.name())
                .append(String.format(Locale.ROOT, " x%.2f", value)));
        if (d.income() != null) {
            bonuses.append(bonuses.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "income %.0f / %d min", d.income().amount(),
                    d.income().interval().toMinutes()));
        }
        messages.send(sender, "info.lines",
                Messages.mini("territory", d.displayName()),
                Messages.text("id", d.id()),
                Messages.text("type", d.type().name()),
                Messages.text("status", s.state().name()),
                Messages.text("owner", names.name(s.ownerFactionId())),
                Messages.text("capturer", names.name(s.capturingFactionId())),
                Messages.text("progress", FactionNames.percent(s.progressSeconds(), duration)),
                Messages.text("expires", s.owned() ? FactionNames.duration(s.ownedUntil() - now) : "-"),
                Messages.text("cooldown", s.cooldownUntil() > now ? FactionNames.duration(s.cooldownUntil() - now) : "-"),
                Messages.text("bonuses", bonuses.isEmpty() ? "-" : bonuses.toString()),
                Messages.text("world", d.world()),
                Messages.text("x", (int) d.captureZone().x()),
                Messages.text("y", (int) d.captureZone().y()),
                Messages.text("z", (int) d.captureZone().z()));
    }

    private void map(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        Location here = player.getLocation();
        messages.send(sender, "map.header");
        for (TerritoryDefinition def : territories.registry().enabled()) {
            TerritoryState s = state(def);
            TagResolver[] base = {Messages.mini("territory", def.displayName()), Messages.text("status", s.state().name()),
                    Messages.text("owner", names.name(s.ownerFactionId()))};
            if (!def.world().equals(here.getWorld().getName())) {
                messages.send(sender, "map.other-world", concat(base, Messages.text("world", def.world())));
                continue;
            }
            double dx = def.captureZone().x() - here.getX();
            double dz = def.captureZone().z() - here.getZ();
            int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            messages.send(sender, "map.entry", concat(base, Messages.text("distance", distance),
                    Messages.text("direction", direction(dx, dz))));
        }
    }

    static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) {
            angle += 360;
        }
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return points[(int) Math.round(angle / 45.0) % 8];
    }

    private void leaderboard(CommandSender sender, String metricArg) {
        String metric = metricArg.isEmpty() ? "xp" : metricArg;
        ToLongFunction<FactionProgress> fn = switch (metric) {
            case "xp" -> FactionProgress::xp;
            case "reputation" -> FactionProgress::reputation;
            case "captures" -> FactionProgress::captures;
            case "income" -> p -> Math.round(p.incomeEarned());
            default -> null;
        };
        if (fn == null) {
            usage(sender, "/drugrun territory leaderboard [xp|reputation|captures|income]");
            return;
        }
        List<FactionProgress> top = progression.top(fn, 10);
        if (top.isEmpty()) {
            messages.send(sender, "leaderboard.empty");
            return;
        }
        messages.send(sender, "leaderboard.header", Messages.text("metric", metric));
        int rank = 1;
        for (FactionProgress p : top) {
            messages.send(sender, "leaderboard.entry", Messages.text("rank", rank++), Messages.text("faction", names.name(p.factionId())),
                    Messages.text("value", fn.applyAsLong(p)));
        }
    }

    private void factionStats(CommandSender sender) {
        FactionRef faction = ownFaction(sender);
        if (faction == null) {
            return;
        }
        FactionProgress p = progression.get(faction.id());
        List<String> owned = territories.ownedBy(faction.id()).stream().map(d -> Messages.plain(d.displayName())).toList();
        messages.send(sender, "faction.stats",
                Messages.text("faction", faction.name()),
                Messages.text("territories", owned.isEmpty() ? "-" : String.join(", ", owned)),
                Messages.text("captures", p.captures()),
                Messages.text("defenses", p.defenses()),
                Messages.text("raids", p.raidVictories()),
                Messages.text("control", FactionNames.duration(p.controlSeconds() * 1000)),
                Messages.text("harvests", p.harvests()),
                Messages.text("income", String.format(Locale.ROOT, "%.2f", p.incomeEarned())),
                Messages.text("sold", p.itemsSold()),
                Messages.text("sales", String.format(Locale.ROOT, "%.2f", p.salesValue())),
                Messages.text("processed", p.jobsProcessed()));
    }

    private void factionProgression(CommandSender sender) {
        FactionRef faction = ownFaction(sender);
        if (faction == null) {
            return;
        }
        FactionProgress p = progression.get(faction.id());
        Settings.Progression cfg = progression.settings();
        Settings.Level level = cfg.levelFor(p.xp());
        Optional<Settings.Level> next = cfg.levels().stream().filter(l -> l.number() == level.number() + 1).findFirst();
        messages.send(sender, "faction.progression",
                Messages.text("faction", faction.name()),
                Messages.text("level", level.number()),
                Messages.text("xp", p.xp()),
                Messages.text("next", next.map(l -> String.valueOf(l.xpRequired())).orElse("max")),
                Messages.text("reputation", p.reputation()),
                Messages.text("limit", progression.territoryLimit(faction.id())));
    }

    private void factionUpgrades(CommandSender sender) {
        FactionRef faction = ownFaction(sender);
        if (faction == null) {
            return;
        }
        long xp = progression.get(faction.id()).xp();
        messages.send(sender, "faction.upgrades.header", Messages.text("faction", faction.name()));
        for (Settings.Level level : progression.settings().levels()) {
            messages.send(sender, "faction.upgrades.entry", Messages.text("level", level.number()),
                    Messages.text("xp", level.xpRequired()), Messages.text("slots", level.extraTerritorySlots()),
                    Messages.text("status", xp >= level.xpRequired() ? "unlocked" : "locked"));
        }
        messages.send(sender, "faction.upgrades.native");
    }

    // ---- admin commands -----------------------------------------------------------------------

    private void admin(CommandSender sender, String[] args) {
        String sub = arg(args, 1);
        switch (sub) {
            case "reload" -> {
                if (!perm(sender, "admin.reload")) {
                    return;
                }
                List<String> problems = reloader.reload();
                if (problems.isEmpty()) {
                    messages.send(sender, "admin.reloaded");
                    audit(sender, "admin.reload", null, null);
                } else {
                    messages.send(sender, "admin.reload-failed", Messages.text("errors", String.join("\n", problems)));
                }
            }
            case "debug" -> {
                if (perm(sender, "admin.debug")) {
                    for (String line : diagnostics.lines()) {
                        sender.sendMessage(line);
                    }
                }
            }
            case "territory" -> adminTerritory(sender, args);
            case "faction" -> {
                if (!arg(args, 2).equals("resetstats")) {
                    usage(sender, "/drugrun admin faction resetstats <faction>");
                    return;
                }
                if (!perm(sender, "admin.faction.resetstats")) {
                    return;
                }
                String tag = rawArg(args, 3);
                Optional<FactionRef> f = tag.isEmpty() ? Optional.empty() : factions.byTag(tag);
                if (f.isEmpty()) {
                    messages.send(sender, "general.unknown-faction", Messages.text("faction", tag));
                    return;
                }
                if (!arg(args, 4).equals("confirm")) {
                    messages.send(sender, "admin.confirm", Messages.text("command",
                            "/drugrun admin faction resetstats " + tag + " confirm"));
                    return;
                }
                progression.reset(f.get().id());
                audit(sender, "admin.faction.resetstats", f.get().id(), f.get().name());
                messages.send(sender, "admin.resetstats", Messages.text("faction", f.get().name()));
            }
            case "economy" -> {
                if (!arg(args, 2).equals("audit")) {
                    usage(sender, "/drugrun admin economy audit [all|paid|failed|unknown]");
                    return;
                }
                if (perm(sender, "admin.economy.audit")) {
                    economyAudit(sender, arg(args, 3));
                }
            }
            default -> {
                if (perm(sender, "admin")) {
                    messages.send(sender, "help.admin");
                }
            }
        }
    }

    private void adminTerritory(CommandSender sender, String[] args) {
        String action = arg(args, 2);
        String id = arg(args, 3);
        if (action.isEmpty() || id.isEmpty()) {
            usage(sender, "/drugrun admin territory <create|delete|setcapture|setregion|enable|disable|reset|forcecapture> <id>");
            return;
        }
        String node = switch (action) {
            case "enable", "disable" -> "admin.territory.create";
            default -> "admin.territory." + action;
        };
        if (!Set.of("create", "delete", "setcapture", "setregion", "enable", "disable", "reset", "forcecapture").contains(action)) {
            messages.send(sender, "general.unknown-command");
            return;
        }
        if (!perm(sender, node)) {
            return;
        }
        if (action.equals("create")) {
            create(sender, id, arg(args, 4));
            return;
        }
        Optional<TerritoryDefinition> existing = territories.registry().get(id);
        if (existing.isEmpty()) {
            messages.send(sender, "general.unknown-territory", Messages.text("id", id));
            return;
        }
        TerritoryDefinition def = existing.get();
        switch (action) {
            case "delete" -> {
                if (!arg(args, 4).equals("confirm")) {
                    messages.send(sender, "admin.confirm", Messages.text("command", "/drugrun admin territory delete " + id + " confirm"));
                    return;
                }
                try {
                    editor.delete(id);
                } catch (IOException e) {
                    fileError(sender, e);
                    return;
                }
                audit(sender, "admin.territory.delete", id, null);
                messages.send(sender, "admin.deleted", Messages.text("id", id));
            }
            case "setcapture" -> {
                Player player = player(sender);
                if (player == null) {
                    return;
                }
                double radius = def.captureZone().radius();
                if (!arg(args, 4).isEmpty()) {
                    try {
                        radius = Double.parseDouble(arg(args, 4));
                    } catch (NumberFormatException e) {
                        radius = -1;
                    }
                    if (!(radius >= 1 && radius <= 64)) {
                        messages.send(sender, "admin.invalid", Messages.text("reason", "radius must be between 1 and 64"));
                        return;
                    }
                }
                Location l = player.getLocation();
                if (!l.getWorld().getName().equals(def.world())) {
                    messages.send(sender, "admin.invalid", Messages.text("reason", "you must be in world " + def.world()));
                    return;
                }
                edit(sender, def.withCaptureZone(new CaptureZone(l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, radius)),
                        "admin.territory.setcapture", "admin.capture-set");
            }
            case "setregion" -> {
                Player player = player(sender);
                if (player == null) {
                    return;
                }
                String corner = arg(args, 4);
                if (!corner.equals("pos1") && !corner.equals("pos2")) {
                    usage(sender, "/drugrun admin territory setregion " + id + " <pos1|pos2>");
                    return;
                }
                Location l = player.getLocation();
                if (!l.getWorld().getName().equals(def.world())) {
                    messages.send(sender, "admin.invalid", Messages.text("reason", "you must be in world " + def.world()));
                    return;
                }
                Region r = def.region();
                Region updated = corner.equals("pos1")
                        ? Region.of(def.world(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), r.maxX(), r.maxY(), r.maxZ())
                        : Region.of(def.world(), r.minX(), r.minY(), r.minZ(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                edit(sender, def.withRegion(updated), "admin.territory.setregion", "admin.region-set");
            }
            case "enable", "disable" -> edit(sender, def.withEnabled(action.equals("enable")), "admin.territory." + action,
                    action.equals("enable") ? "admin.enabled" : "admin.disabled");
            case "reset" -> {
                territories.reset(id);
                audit(sender, "admin.territory.reset", id, null);
                messages.send(sender, "admin.reset", Messages.mini("territory", def.displayName()));
            }
            case "forcecapture" -> {
                String tag = rawArg(args, 4);
                Optional<FactionRef> f = tag.isEmpty() ? Optional.empty() : factions.byTag(tag);
                if (f.isEmpty()) {
                    messages.send(sender, "general.unknown-faction", Messages.text("faction", tag));
                    return;
                }
                if (!def.enabled()) {
                    messages.send(sender, "admin.invalid", Messages.text("reason", "the territory is disabled"));
                    return;
                }
                territories.forceCapture(id, f.get().id());
                audit(sender, "admin.territory.forcecapture", id, "faction=" + f.get().id() + " (" + f.get().name() + ")");
                messages.send(sender, "admin.forcecaptured", Messages.mini("territory", def.displayName()),
                        Messages.text("faction", f.get().name()));
            }
            default -> messages.send(sender, "general.unknown-command");
        }
    }

    private void create(CommandSender sender, String id, String typeArg) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        String clean = id.toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9_\\-]{1,32}")) {
            messages.send(sender, "admin.invalid", Messages.text("reason", "ids use 1-32 characters of a-z, 0-9, _ or -"));
            return;
        }
        if (territories.registry().get(clean).isPresent()) {
            messages.send(sender, "admin.invalid", Messages.text("reason", "territory " + clean + " already exists"));
            return;
        }
        TerritoryType type = TerritoryType.CUSTOM;
        if (!typeArg.isEmpty()) {
            try {
                type = TerritoryType.valueOf(typeArg.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                messages.send(sender, "admin.invalid", Messages.text("reason", "type must be one of " + Arrays.toString(TerritoryType.values())));
                return;
            }
        }
        Location l = player.getLocation();
        World world = l.getWorld();
        Region region = Region.of(world.getName(), l.getBlockX() - 24, world.getMinHeight(), l.getBlockZ() - 24,
                l.getBlockX() + 24, world.getMaxHeight() - 1, l.getBlockZ() + 24);
        TerritoryDefinition def = new TerritoryDefinition(clean, "<yellow>" + clean + "</yellow>", type, true, region,
                new CaptureZone(l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, 8), TerritoryDefinition.CaptureOverrides.NONE,
                Set.of(), List.of(), TerritoryDefinition.Protection.ARENA, TerritoryDefinition.PvpRule.DEFAULT, Map.of(), null,
                TerritoryDefinition.Rewards.NONE);
        edit(sender, def, "admin.territory.create", "admin.created");
    }

    private void edit(CommandSender sender, TerritoryDefinition def, String auditAction, String successKey) {
        List<String> problems;
        try {
            problems = editor.save(def);
        } catch (IOException e) {
            fileError(sender, e);
            return;
        }
        if (!problems.isEmpty()) {
            messages.send(sender, "admin.invalid", Messages.text("reason", String.join(" ", problems)));
            return;
        }
        Region r = def.region();
        audit(sender, auditAction, def.id(), "region=" + r.world() + " " + r.minX() + "," + r.minY() + "," + r.minZ() + " -> "
                + r.maxX() + "," + r.maxY() + "," + r.maxZ() + " capture=" + (int) def.captureZone().x() + "," + (int) def.captureZone().y()
                + "," + (int) def.captureZone().z() + " r=" + def.captureZone().radius() + " enabled=" + def.enabled());
        messages.send(sender, successKey, Messages.mini("territory", def.displayName()), Messages.text("id", def.id()));
    }

    private void economyAudit(CommandSender sender, String filter) {
        PayoutRepository.Status status = switch (filter) {
            case "", "unknown" -> PayoutRepository.Status.UNKNOWN;
            case "failed" -> PayoutRepository.Status.FAILED;
            case "paid" -> PayoutRepository.Status.PAID;
            case "pending" -> PayoutRepository.Status.PENDING;
            default -> null;
        };
        if (status == null && !filter.equals("all")) {
            usage(sender, "/drugrun admin economy audit [all|paid|failed|unknown]");
            return;
        }
        payouts.recent(status, 15).whenComplete((rows, err) -> mainThread.execute(() -> {
            if (err != null) {
                logger.log(Level.SEVERE, "Economy audit query failed", err);
                messages.send(sender, "general.error");
                return;
            }
            if (rows.isEmpty()) {
                messages.send(sender, "admin.audit.empty", Messages.text("status", filter.isEmpty() ? "unknown" : filter));
                return;
            }
            messages.send(sender, "admin.audit.header", Messages.text("status", filter.isEmpty() ? "unknown" : filter));
            for (PayoutRepository.Payout row : rows) {
                messages.send(sender, "admin.audit.entry",
                        Messages.text("time", TIME.format(Instant.ofEpochMilli(row.createdAt()))),
                        Messages.text("territory", row.territoryId()),
                        Messages.text("faction", names.name(row.factionId())),
                        Messages.text("amount", String.format(Locale.ROOT, "%.2f", row.amount())),
                        Messages.text("status", row.status().name()),
                        Messages.text("detail", row.detail() == null ? "" : row.detail()));
            }
        }));
        audit(sender, "admin.economy.audit", filter.isEmpty() ? "unknown" : filter, null);
    }

    // ---- tab completion -----------------------------------------------------------------------

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        int n = args.length;
        String last = n == 0 ? "" : args[n - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        String a0 = n > 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        String a1 = n > 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        String a2 = n > 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (n <= 1) {
            options.addAll(List.of("help", "territories", "territory", "faction"));
            if (sender.hasPermission(P + "admin")) {
                options.add("admin");
            }
        } else if (n == 2) {
            switch (a0) {
                case "territory" -> options.addAll(List.of("info", "map", "leaderboard"));
                case "faction" -> options.addAll(List.of("stats", "progression", "upgrades"));
                case "admin" -> {
                    if (sender.hasPermission(P + "admin")) {
                        options.addAll(List.of("reload", "debug", "territory", "faction", "economy"));
                    }
                }
                default -> {
                }
            }
        } else if (a0.equals("territory") && n == 3) {
            if (a1.equals("info")) {
                options.addAll(ids());
            } else if (a1.equals("leaderboard")) {
                options.addAll(METRICS);
            }
        } else if (a0.equals("admin") && sender.hasPermission(P + "admin")) {
            if (a1.equals("territory")) {
                if (n == 3) {
                    options.addAll(List.of("create", "delete", "setcapture", "setregion", "enable", "disable", "reset", "forcecapture"));
                } else if (n == 4 && !a2.equals("create")) {
                    options.addAll(ids());
                } else if (n == 4) {
                    options.add("<id>");
                } else if (n == 5) {
                    switch (a2) {
                        case "create" -> Stream.of(TerritoryType.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).forEach(options::add);
                        case "setregion" -> options.addAll(List.of("pos1", "pos2"));
                        case "setcapture" -> options.add("8");
                        case "delete" -> options.add("confirm");
                        case "forcecapture" -> options.add("<faction>");
                        default -> {
                        }
                    }
                }
            } else if (a1.equals("faction") && n == 3) {
                options.add("resetstats");
            } else if (a1.equals("economy")) {
                if (n == 3) {
                    options.add("audit");
                } else if (n == 4) {
                    options.addAll(List.of("all", "paid", "failed", "unknown"));
                }
            }
        }
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<String> ids() {
        return territories.registry().all().stream().map(TerritoryDefinition::id).toList();
    }

    private TerritoryState state(TerritoryDefinition def) {
        return territories.state(def.id()).orElse(TerritoryState.available(def.id()));
    }

    private FactionRef ownFaction(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return null;
        }
        Optional<FactionRef> f = factions.factionOf(player.getUniqueId());
        if (f.isEmpty()) {
            messages.send(sender, "general.no-faction");
            return null;
        }
        return f.get();
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        messages.send(sender, "general.player-only");
        return null;
    }

    private boolean perm(CommandSender sender, String node) {
        if (sender.hasPermission(P + node)) {
            return true;
        }
        messages.send(sender, "general.no-permission");
        return false;
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "general.usage", Messages.text("usage", usage));
    }

    private void fileError(CommandSender sender, IOException e) {
        logger.log(Level.SEVERE, "Could not write " + TerritoryEditor.FILE, e);
        messages.send(sender, "general.error");
    }

    private void audit(CommandSender sender, String action, String target, String details) {
        audit.record(sender instanceof Player p ? p.getName() + "/" + p.getUniqueId() : "CONSOLE", action, target, details);
    }

    private static String arg(String[] args, int index) {
        return index < args.length ? args[index].toLowerCase(Locale.ROOT) : "";
    }

    private static String rawArg(String[] args, int index) {
        return index < args.length ? args[index] : "";
    }

    private static TagResolver[] concat(TagResolver[] a, TagResolver... b) {
        TagResolver[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
