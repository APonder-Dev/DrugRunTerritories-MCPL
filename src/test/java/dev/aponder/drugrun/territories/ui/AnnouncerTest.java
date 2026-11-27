/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.CaptureEvent;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Chat announcements are throttled so edge-hovering players cannot flood chat.
 *
 * <p>Uses the default settings, where {@code announcements.repeat-cooldown-seconds} is 30. The
 * announcer is given a fake clock, so the tests move time forward instead of waiting.
 */
class AnnouncerTest {

    private ServerMock server;
    /** the one online player; every announcement lands in their chat, where the tests count it */
    private PlayerMock player;
    /** fake clock in epoch millis, read by the announcer's cooldown */
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Announcer announcer;
    /** the territory every event in these tests is about */
    private final TerritoryDefinition docks = Fixtures.territory("docks", 1000, 1000, Map.of());
    private final TerritoryState state = TerritoryState.available("docks");

    /**
     * Starts a mock server with one player and builds an announcer with the bundled messages.
     * MockBukkit cannot broadcast to the whole server, so the announcer is given the online
     * players as its audience instead.
     */
    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();
        FakeFactions factions = new FakeFactions();
        factions.add("1", "Alpha");
        Messages messages = new Messages();
        messages.load(Fixtures.bundled("messages.yml"));
        Settings settings = Fixtures.settings();
        announcer = new Announcer(messages, new FactionNames(factions), () -> settings, Fixtures.LOG, clock::get,
                () -> Audience.audience(server.getOnlinePlayers()));
    }

    /** Shuts the mock server down so the next test starts clean. */
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** @return how many chat messages the player received since the last call (and clears them) */
    private int drain() {
        int n = 0;
        while (player.nextComponentMessage() != null) {
            n++;
        }
        return n;
    }

    /** A capture starts and fails at once, as when a player steps into the zone and straight back out. */
    private void flap() {
        announcer.onCaptureEvent(docks, new CaptureEvent.Started("docks", "1", false), state);
        announcer.onCaptureEvent(docks, new CaptureEvent.Failed("docks", "1", CaptureEvent.FailReason.ABANDONED), state);
    }

    @Test
    @DisplayName("Repeated start/fail flapping is announced once per repeat cooldown")
    void startFailFlapping() {
        // The first start and failure are always announced.
        flap();
        assertEquals(2, drain(), "first start and its failure are announced");
        // Ten more flaps, two seconds apart, all inside the 30-second cooldown.
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(2_000);
            flap();
        }
        assertEquals(0, drain(), "a start inside the cooldown and its failure stay quiet");
        // Once the cooldown has passed, the next start and failure are announced again.
        clock.addAndGet(30_000);
        flap();
        assertEquals(2, drain(), "announced again after the cooldown");
    }

    @Test
    @DisplayName("Contested messages are throttled; completions never are")
    void contestedThrottled() {
        // Five rounds, one second apart: one contested event and one completion each.
        for (int i = 0; i < 5; i++) {
            announcer.onCaptureEvent(docks, new CaptureEvent.Contested("docks", "1", java.util.Set.of("2")), state);
            announcer.onCaptureEvent(docks, new CaptureEvent.Completed("docks", "1", null, java.util.UUID.randomUUID()), state);
            clock.addAndGet(1_000);
        }
        // Only the first contested message gets through the cooldown; all five completions do.
        assertEquals(1 + 5, drain(), "one contested message and five completions");
    }
}
