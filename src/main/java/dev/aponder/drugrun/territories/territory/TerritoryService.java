/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.bonus.BonusCalculator;
import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.ProgressDelta;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns territory runtime state and runs the capture engine. Main thread only, apart from the
 * storage futures it starts.
 *
 * <p>Persistence: a state change that alters the capture state or owner is saved at once;
 * progress-only changes are saved every {@code performance.progress-save-interval-seconds}.
 * A failed save leaves the territory marked dirty, so the next save retries it.
 */
public final class TerritoryService {

    private final TerritoryRepository repository;
    private final FactionProvider factions;
    private final ProgressionService progression;
    private final ParticipantSource participants;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Supplier<LocalTime> timeOfDay;
    private final Logger logger;
    private final List<CaptureListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Settings settings;
    private volatile TerritoryRegistry registry;
    private final Map<String, TerritoryState> states = new LinkedHashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Map<String, Double> controlSeconds = new HashMap<>();
    private long lastTick = -1;
    private long lastProgressSave;
    private long lastControlFlush;

    public TerritoryService(Settings settings, TerritoryRegistry registry, TerritoryRepository repository,
                            FactionProvider factions, ProgressionService progression, ParticipantSource participants,
                            Executor mainThread, LongSupplier clock, Supplier<LocalTime> timeOfDay, Logger logger) {
        this.settings = settings;
        this.registry = registry;
        this.repository = repository;
        this.factions = factions;
        this.progression = progression;
        this.participants = participants;
        this.mainThread = mainThread;
        this.clock = clock;
        this.timeOfDay = timeOfDay;
        this.logger = logger;
    }

    public void addListener(CaptureListener listener) {
        listeners.add(listener);
    }

    /** Installs stored state (startup). Territories without stored state start AVAILABLE. */
    public void initialize(Map<String, TerritoryState> stored) {
        states.clear();
        for (TerritoryDefinition def : registry.all()) {
            states.put(def.id(), stored.getOrDefault(def.id(), TerritoryState.available(def.id())));
        }
        long now = clock.getAsLong();
        lastProgressSave = now;
        lastControlFlush = now;
        lastTick = -1;
    }

    // ---- queries ------------------------------------------------------------------------------

    public Settings settings() {
        return settings;
    }

    public TerritoryRegistry registry() {
        return registry;
    }

    public Optional<TerritoryState> state(String territoryId) {
        return Optional.ofNullable(states.get(territoryId));
    }

    /** @return enabled territories currently owned by the faction */
    public List<TerritoryDefinition> ownedBy(String factionId) {
        List<TerritoryDefinition> out = new ArrayList<>();
        for (TerritoryDefinition def : registry.enabled()) {
            TerritoryState s = states.get(def.id());
            if (s != null && factionId.equals(s.ownerFactionId())) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * @return the faction's combined multiplier for {@code type} across every territory it owns,
     *         after stacking rules and the hard cap; 1.0 when it owns none
     */
    public double bonusMultiplier(String factionId, BonusType type) {
        List<Double> values = new ArrayList<>();
        for (TerritoryDefinition def : ownedBy(factionId)) {
            Double v = def.bonuses().get(type);
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return 1.0;
        }
        Settings.Bonuses b = settings.bonuses();
        return BonusCalculator.combine(values, b.stacking(), b.hardCap());
    }

    // ---- ticking ------------------------------------------------------------------------------

    /** Evaluates every enabled territory once. Called by a repeating main-thread task. */
    public void tick() {
        Settings cfg = settings;
        if (!cfg.territoriesEnabled()) {
            return;
        }
        long now = clock.getAsLong();
        double nominal = cfg.tickIntervalTicks() / 20.0;
        double elapsed = lastTick < 0 ? nominal : Math.min((now - lastTick) / 1000.0, nominal * 2);
        lastTick = now;
        LocalTime time = timeOfDay.get();

        for (TerritoryDefinition def : registry.enabled()) {
            TerritoryState before = states.computeIfAbsent(def.id(), TerritoryState::available);
            CaptureEngine.Result result;
            try {
                // Safety net for disbands that happened while this plugin was not listening.
                if (before.owned() && factions.byId(before.ownerFactionId()).isEmpty()) {
                    CaptureEngine.Result revoked = CaptureEngine.removeFaction(before, before.ownerFactionId(),
                            def.rules(cfg.capture()), now);
                    apply(def, before, revoked);
                    before = revoked.state();
                }
                Map<String, Integer> present = participants.count(def, now);
                CaptureEngine.Tick tick = new CaptureEngine.Tick(now, Math.max(0, elapsed), def.scheduleOpen(time), present,
                        eligibility(def));
                result = CaptureEngine.tick(before, def.rules(cfg.capture()), tick);
            } catch (FactionLookupException e) {
                // Fail closed: no progress, no completion while factions cannot be checked.
                if (cfg.debug()) {
                    logger.warning("Skipping capture tick for " + def.id() + ": " + e.getMessage());
                }
                continue;
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Capture tick failed for " + def.id(), e);
                continue;
            }
            apply(def, before, result);
            if (result.state().state() == CaptureState.CONTROLLED && result.state().owned()) {
                controlSeconds.merge(result.state().ownerFactionId(), elapsed, Double::sum);
            }
        }

        if (now - lastControlFlush >= 60_000) {
            flushControlTime();
            lastControlFlush = now;
        }
        if (now - lastProgressSave >= cfg.performance().progressSaveInterval().toMillis()) {
            saveDirty();
            lastProgressSave = now;
        }
    }

    private CaptureEngine.Eligibility eligibility(TerritoryDefinition def) {
        return new CaptureEngine.Eligibility() {
            @Override
            public boolean mayCapture(String factionId) {
                Optional<FactionRef> f = factions.byId(factionId);
                return f.isPresent() && f.get().normal() && def.allows(f.get().id(), f.get().name());
            }

            @Override
            public boolean canHoldAnother(String factionId) {
                long held = ownedBy(factionId).stream().filter(d -> !d.id().equals(def.id())).count();
                return held < progression.territoryLimit(factionId);
            }

            @Override
            public boolean friendlyWith(String factionId, String ownerId) {
                Optional<FactionRef> a = factions.byId(factionId);
                Optional<FactionRef> b = factions.byId(ownerId);
                if (a.isEmpty() || b.isEmpty()) {
                    return false;
                }
                FactionRelation r = factions.relation(a.get(), b.get());
                return r == FactionRelation.ALLY || r == FactionRelation.TRUCE || r == FactionRelation.SAME_FACTION;
            }
        };
    }

    private void apply(TerritoryDefinition def, TerritoryState before, CaptureEngine.Result result) {
        TerritoryState after = result.state();
        if (!after.equals(before)) {
            states.put(def.id(), after);
            boolean structural = after.state() != before.state() || !java.util.Objects.equals(after.ownerFactionId(), before.ownerFactionId())
                    || !java.util.Objects.equals(after.capturingFactionId(), before.capturingFactionId());
            if (structural) {
                save(after);
            } else {
                dirty.add(def.id());
            }
        }
        for (CaptureEvent event : result.events()) {
            for (CaptureListener listener : listeners) {
                try {
                    listener.onCaptureEvent(def, event, after);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Capture listener failed for " + event, e);
                }
            }
        }
    }

    private void flushControlTime() {
        long perMinute = progression.settings().controlXpPerMinute();
        for (Map.Entry<String, Double> e : controlSeconds.entrySet()) {
            long seconds = Math.round(e.getValue());
            if (seconds <= 0) {
                continue;
            }
            progression.add(e.getKey(), ProgressDelta.control(seconds, perMinute * seconds / 60));
        }
        controlSeconds.clear();
    }

    // ---- persistence --------------------------------------------------------------------------

    private CompletableFuture<Void> save(TerritoryState state) {
        dirty.remove(state.territoryId());
        return repository.save(state).handle((ok, err) -> {
            if (err != null) {
                logger.log(Level.SEVERE, "Could not save territory " + state.territoryId() + "; will retry", err);
                mainThread.execute(() -> dirty.add(state.territoryId()));
            }
            return null;
        });
    }

    /** Saves every territory with unsaved progress. */
    public CompletableFuture<Void> saveDirty() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String id : List.copyOf(dirty)) {
            TerritoryState s = states.get(id);
            if (s != null) {
                futures.add(save(s));
            } else {
                dirty.remove(id);
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Saves every territory and pending control time; used on shutdown. */
    public CompletableFuture<Void> saveAll() {
        flushControlTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TerritoryState s : states.values()) {
            futures.add(save(s));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    // ---- changes from outside -----------------------------------------------------------------

    /** Applies a reloaded config. */
    public void reconfigure(Settings newSettings) {
        this.settings = newSettings;
    }

    /** Replaces the territory definitions (reload or admin edit). States of removed territories are kept in storage. */
    public void replaceDefinitions(TerritoryRegistry newRegistry) {
        this.registry = newRegistry;
        for (TerritoryDefinition def : newRegistry.all()) {
            states.computeIfAbsent(def.id(), TerritoryState::available);
        }
        states.keySet().removeIf(id -> newRegistry.get(id).isEmpty());
    }

    /** Forgets a deleted territory's stored state. */
    public CompletableFuture<Void> deleteState(String territoryId) {
        states.remove(territoryId);
        dirty.remove(territoryId);
        return repository.delete(territoryId);
    }

    /** Admin reset: territory becomes AVAILABLE with no owner. */
    public void reset(String territoryId) {
        TerritoryDefinition def = registry.get(territoryId).orElseThrow();
        TerritoryState before = states.computeIfAbsent(territoryId, TerritoryState::available);
        apply(def, before, CaptureEngine.reset(before));
    }

    /** Admin force-capture: the faction owns the territory now. */
    public void forceCapture(String territoryId, String factionId) {
        TerritoryDefinition def = registry.get(territoryId).orElseThrow();
        TerritoryState before = states.computeIfAbsent(territoryId, TerritoryState::available);
        apply(def, before, CaptureEngine.forceCapture(before, factionId, def.rules(settings.capture()), clock.getAsLong()));
    }

    /** A faction disbanded: revoke its ownership and drop its capture attempts everywhere. */
    public void factionRemoved(String factionId) {
        long now = clock.getAsLong();
        for (TerritoryDefinition def : registry.all()) {
            TerritoryState before = states.get(def.id());
            if (before == null || !(factionId.equals(before.ownerFactionId()) || factionId.equals(before.capturingFactionId()))) {
                continue;
            }
            apply(def, before, CaptureEngine.removeFaction(before, factionId, def.rules(settings.capture()), now));
        }
        controlSeconds.remove(factionId);
    }
}
