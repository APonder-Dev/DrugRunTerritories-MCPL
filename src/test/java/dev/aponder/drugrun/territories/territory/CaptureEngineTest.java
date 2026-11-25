/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Capture state machine and engine rules (spec tests 11-25). */
class CaptureEngineTest {

    private static final String T = "docks";
    private static final long T0 = 1_000_000_000L;

    /** Mutable eligibility for tests. */
    static final class Rules implements CaptureEngine.Eligibility {
        final Set<String> existing = new HashSet<>(Set.of("a", "b", "c"));
        final Set<String> atLimit = new HashSet<>();
        final Set<String> friendly = new HashSet<>();

        @Override
        public boolean mayCapture(String factionId) {
            return existing.contains(factionId);
        }

        @Override
        public boolean canHoldAnother(String factionId) {
            return !atLimit.contains(factionId);
        }

        @Override
        public boolean friendlyWith(String factionId, String ownerId) {
            return friendly.contains(factionId + "|" + ownerId);
        }
    }

    private final Rules eligibility = new Rules();

    private CaptureEngine.Result tick(TerritoryState s, CaptureRules rules, long now, double elapsed, Map<String, Integer> present) {
        return CaptureEngine.tick(s, rules, new CaptureEngine.Tick(now, elapsed, true, present, eligibility));
    }

    /** Runs {@code seconds} one-second ticks with the same presence. */
    private CaptureEngine.Result run(TerritoryState s, CaptureRules rules, long start, int seconds, Map<String, Integer> present) {
        CaptureEngine.Result r = new CaptureEngine.Result(s, List.of());
        java.util.List<CaptureEvent> all = new java.util.ArrayList<>();
        for (int i = 1; i <= seconds; i++) {
            r = tick(r.state(), rules, start + i * 1000L, 1.0, present);
            all.addAll(r.events());
        }
        return new CaptureEngine.Result(r.state(), all);
    }

    @Test
    @DisplayName("Valid capture: two members start, progress and complete after the duration")
    void validCapture() {
        CaptureEngine.Result r = tick(TerritoryState.available(T), Fixtures.rules(), T0, 1, Map.of("a", 2));
        assertEquals(CaptureState.CAPTURING, r.state().state());
        assertEquals("a", r.state().capturingFactionId());
        assertTrue(r.events().getFirst() instanceof CaptureEvent.Started);

        CaptureEngine.Result done = run(r.state(), Fixtures.rules(), T0, 60, Map.of("a", 2));
        assertEquals(CaptureState.CONTROLLED, done.state().state());
        assertEquals("a", done.state().ownerFactionId());
        assertNotNull(done.state().ownershipId());
        assertEquals(T0 + 60_000 + Duration.ofHours(24).toMillis(), done.state().ownedUntil());
        assertEquals(1, done.events().stream().filter(e -> e instanceof CaptureEvent.Completed).count());
    }

    @Test
    @DisplayName("Insufficient participants never start a capture")
    void insufficientParticipants() {
        CaptureEngine.Result r = run(TerritoryState.available(T), Fixtures.rules(), T0, 30, Map.of("a", 1));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
        assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("Two factions in an available zone: nobody starts")
    void twoFactionsNoStart() {
        CaptureEngine.Result r = tick(TerritoryState.available(T), Fixtures.rules(), T0, 1, Map.of("a", 3, "b", 1));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
    }

    @Test
    @DisplayName("Contested capture pauses progress, then resumes when the zone clears")
    void contestedPauses() {
        TerritoryState s = run(TerritoryState.available(T), Fixtures.rules(), T0, 10, Map.of("a", 2)).state();
        double before = s.progressSeconds();
        CaptureEngine.Result contested = tick(s, Fixtures.rules(), T0 + 20_000, 1, Map.of("a", 2, "b", 1));
        assertEquals(CaptureState.CONTESTED, contested.state().state());
        assertTrue(contested.events().getFirst() instanceof CaptureEvent.Contested);
        TerritoryState held = run(contested.state(), Fixtures.rules(), T0 + 20_000, 20, Map.of("a", 2, "b", 1)).state();
        assertEquals(before, held.progressSeconds(), 1e-9);
        CaptureEngine.Result resumed = tick(held, Fixtures.rules(), T0 + 60_000, 1, Map.of("a", 2));
        assertEquals(CaptureState.CAPTURING, resumed.state().state());
        assertTrue(resumed.events().stream().anyMatch(e -> e instanceof CaptureEvent.Resumed));
        assertEquals(before + 1, resumed.state().progressSeconds(), 1e-9);
    }

    @Test
    @DisplayName("Contested capture with DECAY drains and eventually fails")
    void contestedDecays() {
        CaptureRules decay = Fixtures.rules(Settings.ContestedBehavior.DECAY, Duration.ofMinutes(60), true);
        TerritoryState s = run(TerritoryState.available(T), decay, T0, 5, Map.of("a", 2)).state();
        CaptureEngine.Result r = run(s, decay, T0 + 10_000, 10, Map.of("a", 2, "b", 2));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof CaptureEvent.Failed f && f.reason() == CaptureEvent.FailReason.ABANDONED));
    }

    @Test
    @DisplayName("Player disconnect or death: capturer leaves the zone and progress decays to failure")
    void disconnectDecays() {
        TerritoryState s = run(TerritoryState.available(T), Fixtures.rules(), T0, 5, Map.of("a", 2)).state();
        // One player disconnects/dies: below the minimum.
        CaptureEngine.Result one = tick(s, Fixtures.rules(), T0 + 6_000, 1, Map.of("a", 1));
        assertEquals(CaptureState.CAPTURING, one.state().state());
        assertEquals(s.progressSeconds() - 1, one.state().progressSeconds(), 1e-9);
        // Everybody gone.
        CaptureEngine.Result gone = run(one.state(), Fixtures.rules(), T0 + 7_000, 10, Map.of());
        assertEquals(CaptureState.AVAILABLE, gone.state().state());
        assertNull(gone.state().capturingFactionId());
    }

    @Test
    @DisplayName("Without abandonment decay an empty zone fails the capture immediately")
    void emptyWithoutDecayFails() {
        CaptureRules noDecay = Fixtures.rules(Settings.ContestedBehavior.PAUSE, Duration.ofMinutes(60), false);
        TerritoryState s = run(TerritoryState.available(T), noDecay, T0, 5, Map.of("a", 2)).state();
        assertEquals(CaptureState.CAPTURING, tick(s, noDecay, T0 + 6_000, 1, Map.of("a", 1)).state().state());
        assertEquals(CaptureState.AVAILABLE, tick(s, noDecay, T0 + 6_000, 1, Map.of()).state().state());
    }

    @Test
    @DisplayName("Multiple competing factions can never both complete; only the capturer can")
    void multipleFactions() {
        TerritoryState s = run(TerritoryState.available(T), Fixtures.rules(), T0, 60, Map.of("a", 2)).state();
        CaptureEngine.Result r = run(s, Fixtures.rules(), T0 + 60_000, 30, Map.of("a", 2, "b", 2, "c", 2));
        assertEquals(CaptureState.CONTESTED, r.state().state());
        assertFalse(r.state().owned());
        CaptureEngine.Result done = tick(r.state(), Fixtures.rules(), T0 + 100_000, 1, Map.of("a", 2));
        assertEquals("a", done.state().ownerFactionId());
        assertEquals(1, done.events().stream().filter(e -> e instanceof CaptureEvent.Completed).count());
    }

    @Test
    @DisplayName("Territory expiration releases ownership into cooldown, then availability")
    void expiryAndCooldown() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        long expires = owned.ownedUntil();
        CaptureEngine.Result expired = tick(owned, Fixtures.rules(), expires, 1, Map.of());
        assertEquals(CaptureState.COOLDOWN, expired.state().state());
        assertFalse(expired.state().owned());
        assertTrue(expired.events().getFirst() instanceof CaptureEvent.Expired);
        // Cooldown enforcement: nothing can start during it.
        CaptureEngine.Result during = tick(expired.state(), Fixtures.rules(), expires + 30 * 60_000, 1, Map.of("b", 5));
        assertEquals(CaptureState.COOLDOWN, during.state().state());
        CaptureEngine.Result after = tick(expired.state(), Fixtures.rules(), expires + 60 * 60_000, 1, Map.of("b", 2));
        assertEquals(CaptureState.CAPTURING, after.state().state());
        assertEquals("b", after.state().capturingFactionId());
    }

    @Test
    @DisplayName("Zero cooldown goes straight from expiry to available")
    void zeroCooldown() {
        CaptureRules rules = Fixtures.rules(Settings.ContestedBehavior.PAUSE, Duration.ZERO, true);
        TerritoryState owned = run(TerritoryState.available(T), rules, T0, 61, Map.of("a", 2)).state();
        CaptureEngine.Result r = tick(owned, rules, owned.ownedUntil(), 1, Map.of());
        assertEquals(CaptureState.AVAILABLE, r.state().state());
    }

    @Test
    @DisplayName("Maximum territory limit blocks starting and completing")
    void limitEnforced() {
        eligibility.atLimit.add("a");
        assertEquals(CaptureState.AVAILABLE, tick(TerritoryState.available(T), Fixtures.rules(), T0, 1, Map.of("a", 4)).state().state());
        eligibility.atLimit.clear();
        TerritoryState s = run(TerritoryState.available(T), Fixtures.rules(), T0, 60, Map.of("a", 2)).state();
        eligibility.atLimit.add("a"); // gained another territory meanwhile
        CaptureEngine.Result r = tick(s, Fixtures.rules(), T0 + 61_000, 1, Map.of("a", 2));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof CaptureEvent.Failed f && f.reason() == CaptureEvent.FailReason.LIMIT_REACHED));
    }

    @Test
    @DisplayName("Challenge of a controlled territory, defended by the owner")
    void challengeDefended() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        long afterCooldown = owned.cooldownUntil() + 1;
        CaptureEngine.Result challenge = tick(owned, Fixtures.rules(), afterCooldown, 1, Map.of("b", 2));
        assertEquals(CaptureState.CAPTURING, challenge.state().state());
        assertTrue(challenge.events().getFirst() instanceof CaptureEvent.Started s && s.challenge());
        assertEquals("a", challenge.state().ownerFactionId(), "owner keeps control during a challenge");
        CaptureEngine.Result contested = tick(challenge.state(), Fixtures.rules(), afterCooldown + 1000, 1, Map.of("b", 2, "a", 1));
        assertTrue(contested.state().defendersSeen());
        CaptureEngine.Result failed = run(contested.state(), Fixtures.rules(), afterCooldown + 2000, 5, Map.of("a", 1));
        assertEquals(CaptureState.CONTROLLED, failed.state().state());
        assertEquals("a", failed.state().ownerFactionId());
        assertTrue(failed.events().stream().anyMatch(e -> e instanceof CaptureEvent.Defended));
    }

    @Test
    @DisplayName("Challenge succeeds and transfers ownership with a new ownership id")
    void challengeSucceeds() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        CaptureEngine.Result r = run(owned, Fixtures.rules(), owned.cooldownUntil(), 62, Map.of("b", 2));
        assertEquals("b", r.state().ownerFactionId());
        assertFalse(owned.ownershipId().equals(r.state().ownershipId()));
        assertTrue(r.events().stream().anyMatch(e -> e instanceof CaptureEvent.Completed c && "a".equals(c.previousOwnerId())));
    }

    @Test
    @DisplayName("Challenges are blocked during the post-capture cooldown and for allied factions")
    void challengeRestrictions() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        assertEquals(CaptureState.CONTROLLED, tick(owned, Fixtures.rules(), owned.cooldownUntil() - 1, 1, Map.of("b", 4)).state().state());
        eligibility.friendly.add("b|a");
        assertEquals(CaptureState.CONTROLLED, tick(owned, Fixtures.rules(), owned.cooldownUntil() + 1, 1, Map.of("b", 4)).state().state());
    }

    @Test
    @DisplayName("Faction disband during ownership revokes it and starts the cooldown")
    void disbandRevokes() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        CaptureEngine.Result r = CaptureEngine.removeFaction(owned, "a", Fixtures.rules(), T0 + 100_000);
        assertEquals(CaptureState.COOLDOWN, r.state().state());
        assertFalse(r.state().owned());
        assertTrue(r.events().getFirst() instanceof CaptureEvent.Revoked);
    }

    @Test
    @DisplayName("Capturing faction disbanding ends the capture")
    void disbandWhileCapturing() {
        TerritoryState s = run(TerritoryState.available(T), Fixtures.rules(), T0, 10, Map.of("a", 2)).state();
        eligibility.existing.remove("a");
        CaptureEngine.Result r = tick(s, Fixtures.rules(), T0 + 12_000, 1, Map.of("a", 2));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof CaptureEvent.Failed f && f.reason() == CaptureEvent.FailReason.INELIGIBLE));
    }

    @Test
    @DisplayName("Closed schedule: nothing starts and progress does not grow")
    void scheduleClosed() {
        CaptureEngine.Result r = CaptureEngine.tick(TerritoryState.available(T), Fixtures.rules(),
                new CaptureEngine.Tick(T0, 1, false, Map.of("a", 3), eligibility));
        assertEquals(CaptureState.AVAILABLE, r.state().state());
    }

    @Test
    @DisplayName("Admin force-capture and reset")
    void adminOps() {
        CaptureEngine.Result forced = CaptureEngine.forceCapture(TerritoryState.available(T), "c", Fixtures.rules(), T0);
        assertEquals("c", forced.state().ownerFactionId());
        assertEquals(CaptureState.CONTROLLED, forced.state().state());
        CaptureEngine.Result reset = CaptureEngine.reset(forced.state());
        assertEquals(TerritoryState.available(T), reset.state());
    }

    @Test
    @DisplayName("Ownership expiry during a challenge clears the owner but keeps the capture")
    void expiryDuringChallenge() {
        TerritoryState owned = run(TerritoryState.available(T), Fixtures.rules(), T0, 61, Map.of("a", 2)).state();
        TerritoryState challenge = tick(owned, Fixtures.rules(), owned.cooldownUntil() + 1, 1, Map.of("b", 2)).state();
        CaptureEngine.Result r = tick(challenge, Fixtures.rules(), owned.ownedUntil(), 1, Map.of("b", 2));
        assertFalse(r.state().owned());
        assertEquals(CaptureState.CAPTURING, r.state().state());
        assertTrue(r.events().getFirst() instanceof CaptureEvent.Expired);
    }

    @Test
    @DisplayName("State machine: only documented transitions are legal")
    void legalTransitions() {
        assertTrue(CaptureState.AVAILABLE.canTransitionTo(CaptureState.CAPTURING));
        assertFalse(CaptureState.AVAILABLE.canTransitionTo(CaptureState.CONTROLLED));
        assertFalse(CaptureState.COOLDOWN.canTransitionTo(CaptureState.CAPTURING));
        assertFalse(CaptureState.CONTROLLED.canTransitionTo(CaptureState.AVAILABLE));
        assertTrue(CaptureState.CONTROLLED.canTransitionAdministratively(CaptureState.AVAILABLE));
        assertTrue(CaptureState.EXPIRED.canTransitionTo(CaptureState.COOLDOWN));
        for (CaptureState s : CaptureState.values()) {
            assertTrue(s.canTransitionTo(s));
        }
    }

    @Test
    @DisplayName("Negative or NaN progress is clamped")
    void progressClamped() {
        TerritoryState s = TerritoryState.available(T).withProgress(-5);
        assertEquals(0, s.progressSeconds());
        assertThrows(NullPointerException.class, () -> new TerritoryState(null, CaptureState.AVAILABLE, null, 0, 0, 0, null, 0, null, false));
    }
}
