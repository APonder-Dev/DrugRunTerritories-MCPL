/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.support.Fixtures;
import org.bukkit.GameMode;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Who counts towards a capture: vanished, spectating and unpermitted players do not. */
class BukkitParticipantSourceTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private final Settings.Capture capture = Fixtures.settings().capture();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.addAttachment(plugin, BukkitParticipantSource.CAPTURE_PERMISSION, true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A permitted survival player counts")
    void eligiblePlayer() {
        assertTrue(BukkitParticipantSource.eligible(player, capture));
    }

    @Test
    @DisplayName("A player a vanish plugin has hidden does not count; one it has shown again does")
    @SuppressWarnings("deprecation")
    void vanished() {
        player.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        assertFalse(BukkitParticipantSource.eligible(player, capture));
        player.setMetadata("vanished", new FixedMetadataValue(plugin, false));
        assertTrue(BukkitParticipantSource.eligible(player, capture));
    }

    @Test
    @DisplayName("Spectators and players without the capture permission do not count")
    void spectatorAndPermission() {
        player.setGameMode(GameMode.SPECTATOR);
        assertFalse(BukkitParticipantSource.eligible(player, capture));
        player.setGameMode(GameMode.SURVIVAL);
        PlayerMock other = server.addPlayer();
        assertFalse(BukkitParticipantSource.eligible(other, capture));
    }
}
