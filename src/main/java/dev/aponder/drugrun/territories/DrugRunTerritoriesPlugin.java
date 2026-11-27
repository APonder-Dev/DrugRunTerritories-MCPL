/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories;

import dev.aponder.drugrun.territories.api.ApiImpl;
import dev.aponder.drugrun.territories.api.DrugRunTerritoriesApi;
import dev.aponder.drugrun.territories.api.LootKeyFactionPolicy;
import dev.aponder.drugrun.territories.audit.AuditLog;
import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.command.DrugRunCommand;
import dev.aponder.drugrun.territories.config.ConfigFiles;
import dev.aponder.drugrun.territories.config.LocalConfigFiles;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.config.SettingsLoader;
import dev.aponder.drugrun.territories.druglab.DrugLabIntegration;
import dev.aponder.drugrun.territories.economy.EconomyGateway;
import dev.aponder.drugrun.territories.economy.IncomeService;
import dev.aponder.drugrun.territories.economy.PayoutRepository;
import dev.aponder.drugrun.territories.economy.VaultEconomyGateway;
import dev.aponder.drugrun.territories.faction.FactionChangeListener;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionsUuidProvider;
import dev.aponder.drugrun.territories.hook.TerritoryPlaceholders;
import dev.aponder.drugrun.territories.hook.WorldGuardHook;
import dev.aponder.drugrun.territories.lootkey.FactionKillPolicy;
import dev.aponder.drugrun.territories.progression.CaptureRewardListener;
import dev.aponder.drugrun.territories.progression.ProgressionRepository;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.raid.ArenaProtectionListener;
import dev.aponder.drugrun.territories.raid.DrugLabBlocks;
import dev.aponder.drugrun.territories.raid.ExplosionListener;
import dev.aponder.drugrun.territories.raid.RaidVictoryListener;
import dev.aponder.drugrun.territories.storage.DataStore;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.storage.Migrations;
import dev.aponder.drugrun.territories.territory.AfkTracker;
import dev.aponder.drugrun.territories.territory.BukkitParticipantSource;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryDefinitionCodec;
import dev.aponder.drugrun.territories.territory.TerritoryEditor;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.ui.Announcer;
import dev.aponder.drugrun.territories.ui.CaptureDisplay;
import dev.aponder.drugrun.territories.ui.FactionNames;
import dev.aponder.drugrun.territories.ui.Messages;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * DrugRunTerritories plugin entry point. Wires the services together; gameplay logic lives in the
 * feature packages.
 *
 * <p>Startup reads stored state synchronously, before the server starts ticking, so territories
 * are never evaluated with missing data. After startup every database call is asynchronous.
 */
public final class DrugRunTerritoriesPlugin extends JavaPlugin implements Listener {

    private static final long STARTUP_DB_TIMEOUT_SECONDS = 30;

    private ConfigFiles files;
    private volatile Settings settings;
    private DataStore dataStore;
    private FactionProvider factions;
    private Messages messages;
    private TerritoryService territories;
    private ProgressionService progression;
    private IncomeService income;
    private PayoutRepository payouts;
    private CaptureDisplay display;
    private AfkTracker afk;
    private EconomyGateway economy = EconomyGateway.NONE;
    private volatile DrugLabBlocks drugLabBlocks = DrugLabBlocks.NONE;
    private boolean drugLabActive;
    private boolean worldGuardActive;
    private boolean placeholdersActive;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Executor mainThread = task -> {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    };

    @Override
    public void onEnable() {
        try {
            start();
        } catch (StartupException e) {
            getLogger().severe("DrugRunTerritories cannot start: " + e.getMessage());
            for (String detail : e.details) {
                getLogger().severe(" - " + detail);
            }
            shutdownStorage();
            getServer().getPluginManager().disablePlugin(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "DrugRunTerritories failed to start", e);
            shutdownStorage();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void start() {
        files = new LocalConfigFiles(getDataFolder().toPath(), this::getResource, getLogger());

        // 1. Configuration.
        Settings loaded = loadSettings();
        settings = loaded;
        messages = new Messages();
        messages.load(loadYaml("messages.yml", true));
        List<TerritoryDefinition> definitions = loadDefinitions();

        // 2. Required plugins.
        Plugin fuuid = getServer().getPluginManager().getPlugin("FactionsUUID");
        if (fuuid == null || !fuuid.isEnabled()) {
            throw new StartupException("FactionsUUID is required and is not enabled");
        }
        Plugin core = getServer().getPluginManager().getPlugin("DrugRunCore");
        boolean coreActive = core != null && core.isEnabled();
        if (loaded.integrations().drugRunCoreRequired() && !coreActive) {
            throw new StartupException("integrations.drugruncore.required is true but DrugRunCore is not enabled");
        }
        checkDrugLabRequirement(loaded, definitions);

        // 3. Storage.
        dataStore = new HikariDataStore(loaded.storage(), getDataFolder().toPath(), loaded.performance().asyncThreads(),
                loaded.performance().asyncQueueSize(), getLogger());
        int schema = await(dataStore.transaction(c -> Migrations.migrate(c, dataStore, getLogger())), "database migration");
        getLogger().info("Database ready (" + loaded.storage() + ", schema " + schema + ")");

        // 4. Services.
        factions = new FactionsUuidProvider(fuuid::isEnabled);
        FactionNames names = new FactionNames(factions);
        ProgressionRepository progressionRepo = new ProgressionRepository(dataStore);
        progression = new ProgressionService(progressionRepo, loaded.progression(), loaded.capture().maximumPerFaction(), getLogger());
        progression.enableBatching();
        long raidWindow = System.currentTimeMillis() - loaded.progression().raidVictoryCooldown().toMillis();
        progression.load(await(progressionRepo.loadAll(), "progression"), await(progressionRepo.raidVictoriesSince(raidWindow), "raid history"));

        afk = new AfkTracker();
        TerritoryRepository territoryRepo = new TerritoryRepository(dataStore);
        territories = new TerritoryService(loaded, new TerritoryRegistry(definitions), territoryRepo, factions, progression,
                new BukkitParticipantSource(factions, afk, () -> settings.capture()), mainThread, System::currentTimeMillis,
                LocalTime::now, getLogger());
        territories.initialize(await(territoryRepo.loadAll(), "territory state"));

        AuditLog audit = new AuditLog(dataStore, getLogger());
        payouts = new PayoutRepository(dataStore);
        int interrupted = await(payouts.markPendingUnknown(), "payout recovery");
        if (interrupted > 0) {
            getLogger().warning(interrupted + " payout(s) were interrupted by the last shutdown and are marked UNKNOWN."
                    + " They will not be retried. Review them with /drugrun admin economy audit unknown");
            audit.record("SYSTEM", "economy.recovery", null, interrupted + " pending payout(s) marked UNKNOWN");
        }
        economy = hookEconomy(loaded);
        income = new IncomeService(territories, factions, progression, payouts, audit, economy, loaded.income(), mainThread,
                System::currentTimeMillis, getLogger());
        income.initialize(await(payouts.claimedPeriods(), "payout ledger"));
        territories.addOwnershipEndListener(income::ownershipEnded);

        // 5. Listeners.
        territories.addListener(new CaptureRewardListener(progression, income, territoryRepo, System::currentTimeMillis, getLogger()));
        territories.addListener(new Announcer(messages, names, () -> settings, getLogger()));
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new ExplosionListener(territories::registry, () -> settings, factions, () -> drugLabBlocks), this);
        pm.registerEvents(new ArenaProtectionListener(territories::registry, () -> settings, getLogger()), this);
        pm.registerEvents(new FactionChangeListener(territories, id -> getLogger().info("Faction " + id
                + " disbanded; its territories were released.")), this);
        pm.registerEvents(new RaidVictoryListener(this, factions, progression, () -> settings, messages, System::currentTimeMillis), this);

        BiPredicate<Player, Location> worldGuardBuild = (p, l) -> true;
        Plugin wg = pm.getPlugin("WorldGuard");
        if (loaded.integrations().worldGuard() && loaded.protection().respectWorldGuard() && wg != null && wg.isEnabled()) {
            WorldGuardHook hook = new WorldGuardHook();
            worldGuardBuild = hook::canBuild;
            worldGuardActive = true;
        }
        if (loaded.integrations().drugLabEnabled() && DrugLabIntegration.present()) {
            drugLabBlocks = DrugLabIntegration.register(this, factions, territories, () -> settings, territories::registry, messages,
                    progression, getLogger(), worldGuardBuild);
            drugLabActive = true;
        }

        // 6. Public API for DrugRunCore and other plugins.
        FactionKillPolicy policy = new FactionKillPolicy(factions, () -> settings.killPolicy(), territories::registry);
        getServer().getServicesManager().register(LootKeyFactionPolicy.class, policy, this, ServicePriority.Normal);
        getServer().getServicesManager().register(DrugRunTerritoriesApi.class, new ApiImpl(territories, progression, factions, policy),
                this, ServicePriority.Normal);

        // 7. Commands, placeholders, display.
        TerritoryEditor editor = new TerritoryEditor(files, territories);
        registerCommand("drugrun", "DrugRun territories and faction progression", List.of(),
                new DrugRunCommand(territories, editor, progression, factions, names, messages, audit, payouts, this::reload,
                        this::diagnostics, mainThread, () -> settings, getLogger()));
        display = new CaptureDisplay(territories, messages, names, System::currentTimeMillis);
        Plugin papi = pm.getPlugin("PlaceholderAPI");
        if (loaded.integrations().placeholderApi() && papi != null && papi.isEnabled()) {
            placeholdersActive = new TerritoryPlaceholders(this, territories, progression, factions, names).register();
        }

        scheduleTasks(loaded);
        getLogger().info("Enabled with " + territories.registry().enabled().size() + " active territories"
                + " | DrugLab " + (drugLabActive ? (DrugLabIntegration.hasExtendedApi() ? "on (with bonus hooks)" : "on (1.0.0 API)") : "off") + " | Vault " + (economy.available() ? "on" : "off")
                + " | WorldGuard " + (worldGuardActive ? "on" : "off") + " | PlaceholderAPI " + (placeholdersActive ? "on" : "off")
                + " | DrugRunCore " + (coreActive ? "present (integration pending its release)" : "not installed; using built-in storage"));
    }

    private void scheduleTasks(Settings cfg) {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        int interval = cfg.tickIntervalTicks();
        tasks.add(getServer().getScheduler().runTaskTimer(this, () -> {
            territories.tick();
            display.update();
        }, interval, interval));
        tasks.add(getServer().getScheduler().runTaskTimer(this, income::tick, 20L * 30, 20L * 30));
        tasks.add(getServer().getScheduler().runTaskTimer(this, progression::flush, 20L * 5, 20L * 5));
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        getServer().getServicesManager().unregisterAll(this);
        if (display != null) {
            display.clear();
        }
        if (afk != null) {
            afk.clear();
        }
        if (territories != null) {
            try {
                territories.saveAll().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Could not save every territory on shutdown", e);
            }
        }
        if (progression != null) {
            try {
                progression.flush().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Could not save faction progression on shutdown", e);
            }
        }
        shutdownStorage();
    }

    private void shutdownStorage() {
        if (dataStore != null) {
            dataStore.close();
            dataStore = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        afk.forget(event.getPlayer().getUniqueId());
        display.forget(event.getPlayer());
    }

    // ---- reload -------------------------------------------------------------------------------

    /**
     * Reloads config, messages and territory definitions. Everything is validated first; on any
     * problem nothing changes and the problems are returned. Storage settings and integration
     * switches need a restart.
     */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        Settings next;
        List<TerritoryDefinition> defs;
        YamlConfiguration msgs;
        try {
            SettingsLoader.Result result = SettingsLoader.load(files.load("config.yml"));
            problems.addAll(result.errors());
            next = result.settings();
            TerritoryDefinitionCodec.Result t = TerritoryDefinitionCodec.read(files.load(TerritoryEditor.FILE, false));
            problems.addAll(t.errors());
            defs = t.definitions();
            msgs = files.load("messages.yml");
        } catch (IOException e) {
            return List.of(e.getMessage());
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        if (!next.storage().equals(settings.storage())) {
            getLogger().warning("Storage settings changed; they take effect after a restart.");
        }
        settings = next;
        messages.load(msgs);
        territories.reconfigure(next);
        territories.replaceDefinitions(new TerritoryRegistry(defs));
        progression.reconfigure(next.progression(), next.capture().maximumPerFaction());
        economy = hookEconomy(next);
        income.reconfigure(next.income(), economy);
        display.clear();
        scheduleTasks(next);
        return List.of();
    }

    private List<String> diagnostics() {
        List<String> lines = new ArrayList<>();
        lines.add("§6DrugRunTerritories " + getPluginMeta().getVersion());
        lines.add("§7FactionsUUID: " + (factions.available() ? "§aenabled" : "§cdisabled (fail-closed)"));
        lines.add("§7DrugLab: " + (drugLabActive ? "§aactive" : "§7inactive") + " §7Vault: " + (economy.available() ? "§aactive" : "§7inactive")
                + " §7WorldGuard: " + (worldGuardActive ? "§aactive" : "§7inactive") + " §7PlaceholderAPI: "
                + (placeholdersActive ? "§aactive" : "§7inactive"));
        lines.add("§7Storage: " + settings.storage());
        for (TerritoryDefinition def : territories.registry().all()) {
            territories.state(def.id()).ifPresent(s -> lines.add("§7- " + def.id() + (def.enabled() ? "" : " (disabled)") + ": " + s.state()
                    + " owner=" + s.ownerFactionId() + " capturing=" + s.capturingFactionId()
                    + String.format(java.util.Locale.ROOT, " progress=%.1fs", s.progressSeconds())));
        }
        return lines;
    }

    // ---- startup helpers ----------------------------------------------------------------------

    private Settings loadSettings() {
        SettingsLoader.Result result = SettingsLoader.load(loadYaml("config.yml", true));
        if (!result.ok()) {
            throw new StartupException("config.yml has errors", result.errors());
        }
        return result.settings();
    }

    private List<TerritoryDefinition> loadDefinitions() {
        TerritoryDefinitionCodec.Result result = TerritoryDefinitionCodec.read(loadYaml(TerritoryEditor.FILE, false));
        if (!result.ok()) {
            throw new StartupException("territories.yml has errors", result.errors());
        }
        for (TerritoryDefinition def : result.definitions()) {
            if (def.enabled() && Bukkit.getWorld(def.world()) == null) {
                getLogger().warning("Territory " + def.id() + " is in world '" + def.world() + "', which is not loaded.");
            }
        }
        return result.definitions();
    }

    private YamlConfiguration loadYaml(String name, boolean merge) {
        try {
            return files.load(name, merge);
        } catch (IOException e) {
            throw new StartupException(e.getMessage());
        }
    }

    /**
     * Drug bonuses need DrugLab. With {@code required-for-drug-bonuses: true}, a configuration that
     * grants drug bonuses refuses to start without DrugLab, instead of silently granting nothing.
     */
    private void checkDrugLabRequirement(Settings cfg, List<TerritoryDefinition> definitions) {
        boolean drugBonuses = definitions.stream().filter(TerritoryDefinition::enabled)
                .anyMatch(d -> d.bonuses().keySet().stream().anyMatch(t -> t == BonusType.DEALER_SELL || t == BonusType.PROCESSING_SPEED
                        || t == BonusType.CROP_GROWTH));
        if (!drugBonuses || !cfg.integrations().drugLabRequiredForBonuses()) {
            return;
        }
        if (!cfg.integrations().drugLabEnabled()) {
            throw new StartupException("Territories grant drug bonuses but integrations.druglab.enabled is false."
                    + " Enable it, or set integrations.druglab.required-for-drug-bonuses to false.");
        }
        if (!DrugLabIntegration.present()) {
            throw new StartupException("Territories grant drug bonuses but DrugLab is not enabled."
                    + " Install DrugLab, or set integrations.druglab.required-for-drug-bonuses to false.");
        }
    }

    private EconomyGateway hookEconomy(Settings cfg) {
        Plugin vault = getServer().getPluginManager().getPlugin("Vault");
        if (!cfg.integrations().vault() || vault == null || !vault.isEnabled()) {
            return EconomyGateway.NONE;
        }
        VaultEconomyGateway gateway = new VaultEconomyGateway();
        if (!gateway.hook()) {
            getLogger().warning("Vault is installed but no economy plugin is registered; territory income is paused.");
            return EconomyGateway.NONE;
        }
        return gateway;
    }

    private <T> T await(CompletableFuture<T> future, String what) {
        try {
            return future.get(STARTUP_DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StartupException("interrupted while loading " + what);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new StartupException("could not load " + what + ": " + cause.getMessage());
        }
    }

    /** Startup problem with a readable message. */
    private static final class StartupException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> details;

        StartupException(String message) {
            this(message, List.of());
        }

        StartupException(String message, List<String> details) {
            super(message);
            this.details = details;
        }
    }

    /** @return the live settings snapshot */
    public Settings settings() {
        return settings;
    }
}
