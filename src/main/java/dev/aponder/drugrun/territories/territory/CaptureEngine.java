/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.config.Settings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure capture logic: given a territory's state and who is standing in its zone, decide the next
 * state. No Bukkit calls, no I/O, no clock reads, so every rule is unit-testable and the result is
 * deterministic.
 *
 * <p>The caller runs one territory at a time on the main thread. A territory has at most one
 * capturing faction, and completion happens inside a single {@link #tick} call, so two factions
 * can never both complete the same capture.
 */
public final class CaptureEngine {

    /** Faction checks the engine needs from the outside world. */
    public interface Eligibility {
        /** @return whether the faction exists and this territory allows it */
        boolean mayCapture(String factionId);

        /** @return whether the faction may hold one more territory (this one not counted) */
        boolean canHoldAnother(String factionId);

        /** @return whether {@code factionId} is allied or in truce with {@code ownerId} (allies may not challenge) */
        boolean friendlyWith(String factionId, String ownerId);
    }

    /**
     * Inputs for one evaluation.
     *
     * @param now epoch millis
     * @param elapsedSeconds time since the previous evaluation
     * @param scheduleOpen whether the territory's capture window is open
     * @param present faction id to number of eligible players in the capture zone (only factions with players)
     */
    public record Tick(long now, double elapsedSeconds, boolean scheduleOpen, Map<String, Integer> present,
                       Eligibility eligibility) {
    }

    /** New state plus the events that led to it, in order. */
    public record Result(TerritoryState state, List<CaptureEvent> events) {
        public boolean changed(TerritoryState before) {
            return !state.equals(before);
        }
    }

    private CaptureEngine() {
    }

    /** Evaluates one tick. */
    public static Result tick(TerritoryState before, CaptureRules rules, Tick tick) {
        List<CaptureEvent> events = new ArrayList<>();
        TerritoryState st = before;
        String id = st.territoryId();

        // 1. Ownership expiry.
        if (st.owned() && tick.now() >= st.ownedUntil()) {
            events.add(new CaptureEvent.Expired(id, st.ownerFactionId()));
            if (st.state() == CaptureState.CONTROLLED) {
                st = move(st.withoutOwner().withCooldownUntil(tick.now() + rules.cooldown().toMillis()), CaptureState.EXPIRED);
                if (rules.cooldown().isZero()) {
                    st = move(st, CaptureState.AVAILABLE);
                    events.add(new CaptureEvent.Available(id));
                } else {
                    st = move(st, CaptureState.COOLDOWN);
                }
                return new Result(st, events);
            }
            // A challenge is underway: the owner is gone, the capture continues.
            st = st.withoutOwner();
        }

        // 2. Cooldown.
        if (st.state() == CaptureState.COOLDOWN) {
            if (tick.now() < st.cooldownUntil()) {
                return new Result(st, events);
            }
            st = move(st, CaptureState.AVAILABLE);
            events.add(new CaptureEvent.Available(id));
        }

        Map<String, Integer> present = tick.present();
        int min = rules.minimumPlayers();
        CaptureEngine.Eligibility el = tick.eligibility();

        switch (st.state()) {
            case AVAILABLE -> {
                String only = soleFaction(present);
                if (tick.scheduleOpen() && only != null && present.get(only) >= min
                        && el.mayCapture(only) && el.canHoldAnother(only)) {
                    st = move(st.capturingBy(only), CaptureState.CAPTURING);
                    events.add(new CaptureEvent.Started(id, only, false));
                }
            }
            case CONTROLLED -> {
                String owner = st.ownerFactionId();
                String only = soleFaction(present);
                if (rules.allowChallenges() && tick.scheduleOpen() && tick.now() >= st.cooldownUntil()
                        && only != null && !only.equals(owner) && present.get(only) >= min
                        && el.mayCapture(only) && el.canHoldAnother(only) && !el.friendlyWith(only, owner)) {
                    st = move(st.capturingBy(only), CaptureState.CAPTURING);
                    events.add(new CaptureEvent.Started(id, only, true));
                }
            }
            case CAPTURING, CONTESTED -> st = advanceCapture(st, rules, tick, events);
            default -> {
                // EXPIRED never persists past a tick; COOLDOWN handled above.
            }
        }
        return new Result(st, events);
    }

    private static TerritoryState advanceCapture(TerritoryState st, CaptureRules rules, Tick tick, List<CaptureEvent> events) {
        String id = st.territoryId();
        String capturer = st.capturingFactionId();
        if (capturer == null || !tick.eligibility().mayCapture(capturer)) {
            return fail(st, CaptureEvent.FailReason.INELIGIBLE, events);
        }
        Set<String> others = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : tick.present().entrySet()) {
            if (e.getValue() > 0 && !e.getKey().equals(capturer)) {
                others.add(e.getKey());
            }
        }
        if (st.owned() && others.contains(st.ownerFactionId())) {
            st = st.withDefendersSeen(true);
        }
        double decay = rules.decayPerSecond() * tick.elapsedSeconds();
        int count = tick.present().getOrDefault(capturer, 0);

        // Contested only while the capturer is still in the zone; an empty capturer side is abandonment.
        if (!others.isEmpty() && count > 0) {
            if (st.state() == CaptureState.CAPTURING) {
                st = move(st, CaptureState.CONTESTED);
                events.add(new CaptureEvent.Contested(id, capturer, Set.copyOf(others)));
            }
            if (rules.contestedBehavior() == Settings.ContestedBehavior.DECAY) {
                st = st.withProgress(st.progressSeconds() - decay);
                if (st.progressSeconds() <= 0) {
                    return fail(st, CaptureEvent.FailReason.ABANDONED, events);
                }
            }
            return st;
        }

        if (others.isEmpty() && count >= rules.minimumPlayers()) {
            if (st.state() == CaptureState.CONTESTED) {
                st = move(st, CaptureState.CAPTURING);
                events.add(new CaptureEvent.Resumed(id, capturer));
            }
            if (tick.scheduleOpen()) {
                st = st.withProgress(st.progressSeconds() + tick.elapsedSeconds());
            }
            if (st.progressSeconds() >= rules.durationSeconds()) {
                if (!tick.eligibility().canHoldAnother(capturer)) {
                    return fail(st, CaptureEvent.FailReason.LIMIT_REACHED, events);
                }
                String previous = st.ownerFactionId();
                st = move(st.controlledBy(capturer, tick.now(), rules.ownership().toMillis(), rules.cooldown().toMillis())
                        .withState(st.state()), CaptureState.CONTROLLED);
                events.add(new CaptureEvent.Completed(id, capturer, previous, st.ownershipId()));
            }
            return st;
        }

        // Capturer is below the minimum (or gone).
        if (st.state() == CaptureState.CONTESTED) {
            st = move(st, CaptureState.CAPTURING);
        }
        if (rules.decayWhenAbandoned()) {
            st = st.withProgress(st.progressSeconds() - decay);
            if (st.progressSeconds() <= 0) {
                return fail(st, CaptureEvent.FailReason.ABANDONED, events);
            }
        } else if (count == 0) {
            return fail(st, CaptureEvent.FailReason.ABANDONED, events);
        }
        return st;
    }

    private static TerritoryState fail(TerritoryState st, CaptureEvent.FailReason reason, List<CaptureEvent> events) {
        String id = st.territoryId();
        events.add(new CaptureEvent.Failed(id, st.capturingFactionId(), reason));
        if (st.owned()) {
            if (st.defendersSeen()) {
                events.add(new CaptureEvent.Defended(id, st.ownerFactionId(), st.capturingFactionId()));
            }
            return move(st, CaptureState.CONTROLLED).withoutCapture(CaptureState.CONTROLLED);
        }
        TerritoryState next = move(st, CaptureState.AVAILABLE).withoutCapture(CaptureState.AVAILABLE);
        events.add(new CaptureEvent.Available(id));
        return next;
    }

    // ---- administrative operations ----------------------------------------------------------

    /** Grants ownership to {@code factionId} immediately (admin force-capture). */
    public static Result forceCapture(TerritoryState st, String factionId, CaptureRules rules, long now) {
        requireAdmin(st, CaptureState.CONTROLLED);
        TerritoryState next = st.controlledBy(factionId, now, rules.ownership().toMillis(), rules.cooldown().toMillis());
        return new Result(next, List.of(new CaptureEvent.Completed(st.territoryId(), factionId, st.ownerFactionId(),
                next.ownershipId())));
    }

    /** Clears ownership, capture and cooldown (admin reset). */
    public static Result reset(TerritoryState st) {
        requireAdmin(st, CaptureState.AVAILABLE);
        List<CaptureEvent> events = new ArrayList<>();
        if (st.owned()) {
            events.add(new CaptureEvent.Revoked(st.territoryId(), st.ownerFactionId()));
        }
        events.add(new CaptureEvent.Available(st.territoryId()));
        return new Result(TerritoryState.available(st.territoryId()), events);
    }

    /**
     * Removes every trace of a faction that no longer exists: its ownership is revoked (the
     * territory enters cooldown) and its capture attempt is dropped.
     */
    public static Result removeFaction(TerritoryState st, String factionId, CaptureRules rules, long now) {
        List<CaptureEvent> events = new ArrayList<>();
        TerritoryState next = st;
        if (factionId.equals(next.capturingFactionId())) {
            events.add(new CaptureEvent.Failed(next.territoryId(), factionId, CaptureEvent.FailReason.INELIGIBLE));
            next = next.withoutCapture(next.owned() ? CaptureState.CONTROLLED : CaptureState.AVAILABLE);
        }
        if (factionId.equals(next.ownerFactionId())) {
            events.add(new CaptureEvent.Revoked(next.territoryId(), factionId));
            if (next.state() == CaptureState.CONTROLLED) {
                next = next.withoutOwner().withCooldownUntil(now + rules.cooldown().toMillis());
                requireAdmin(next, rules.cooldown().isZero() ? CaptureState.AVAILABLE : CaptureState.COOLDOWN);
                next = next.withState(rules.cooldown().isZero() ? CaptureState.AVAILABLE : CaptureState.COOLDOWN);
            } else {
                next = next.withoutOwner();
            }
        }
        return new Result(next, events);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String soleFaction(Map<String, Integer> present) {
        String only = null;
        for (Map.Entry<String, Integer> e : present.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = e.getKey();
        }
        return only;
    }

    private static TerritoryState move(TerritoryState st, CaptureState next) {
        if (!st.state().canTransitionTo(next)) {
            throw new IllegalStateException("Illegal capture transition " + st.state() + " -> " + next + " for " + st.territoryId());
        }
        return st.withState(next);
    }

    private static void requireAdmin(TerritoryState st, CaptureState next) {
        if (!st.state().canTransitionAdministratively(next)) {
            throw new IllegalStateException("Illegal administrative transition " + st.state() + " -> " + next);
        }
    }
}
