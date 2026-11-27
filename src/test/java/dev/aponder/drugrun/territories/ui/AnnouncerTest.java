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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Chat announcements are throttled so edge-hovering players cannot flood chat. */
class AnnouncerTest {

    private ServerMock server;
    private PlayerMock player;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Announcer announcer;
    private final TerritoryDefinition docks = Fixtures.territory("docks", 1000, 1000, Map.of());
    private final TerritoryState state = TerritoryState.available("docks");

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
        announcer = new Announcer(messages, new FactionNames(factions), () -> settings, Fixtures.LOG, clock::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private int drain() {
        int n = 0;
        while (player.nextComponentMessage() != null) {
            n++;
        }
        return n;
    }

    private void flap() {
        announcer.onCaptureEvent(docks, new CaptureEvent.Started("docks", "1", false), state);
        announcer.onCaptureEvent(docks, new CaptureEvent.Failed("docks", "1", CaptureEvent.FailReason.ABANDONED), state);
    }

    @Test
    @DisplayName("Repeated start/fail flapping is announced once per repeat cooldown")
    void startFailFlapping() {
        flap();
        assertEquals(2, drain(), "first start and its failure are announced");
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(2_000);
            flap();
        }
        assertEquals(0, drain(), "a start inside the cooldown and its failure stay quiet");
        clock.addAndGet(30_000);
        flap();
        assertEquals(2, drain());
    }

    @Test
    @DisplayName("Contested messages are throttled; completions never are")
    void contestedThrottled() {
        for (int i = 0; i < 5; i++) {
            announcer.onCaptureEvent(docks, new CaptureEvent.Contested("docks", "1", java.util.Set.of("2")), state);
            announcer.onCaptureEvent(docks, new CaptureEvent.Completed("docks", "1", null, java.util.UUID.randomUUID()), state);
            clock.addAndGet(1_000);
        }
        assertEquals(1 + 5, drain());
    }
}
