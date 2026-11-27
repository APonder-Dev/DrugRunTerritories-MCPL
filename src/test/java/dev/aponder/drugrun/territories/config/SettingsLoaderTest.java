/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.bonus.StackingMode;
import dev.aponder.drugrun.territories.storage.Dialect;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettingsLoaderTest {

    private static SettingsLoader.Result loadWith(String path, Object value) {
        YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set(path, value);
        return SettingsLoader.load(yaml);
    }

    @Test
    @DisplayName("Bundled config.yml is valid and matches the proposed defaults")
    void bundledDefaults() {
        Settings s = Fixtures.settings();
        assertEquals(Duration.ofSeconds(600), s.capture().duration());
        assertEquals(2, s.capture().minimumPlayers());
        assertEquals(Duration.ofHours(24), s.capture().ownershipDuration());
        assertEquals(Duration.ofMinutes(60), s.capture().cooldown());
        assertEquals(2, s.capture().maximumPerFaction());
        assertEquals(StackingMode.CAPPED_MULTIPLICATIVE, s.bonuses().stacking());
        assertEquals(Dialect.SQLITE, s.storage().dialect());
        assertTrue(s.killPolicy().preventSameFaction());
        assertEquals(500, s.progression().captureXp());
        assertEquals(5, s.progression().levels().size());
    }

    @Test
    @DisplayName("Out-of-range and wrongly typed values are rejected with clear messages")
    void invalidValues() {
        SettingsLoader.Result r = loadWith("territories.capture.duration-seconds", 3);
        assertFalse(r.ok());
        assertNull(r.settings());
        assertTrue(r.errors().getFirst().contains("duration-seconds"));
        assertFalse(loadWith("territories.capture.minimum-faction-players", "two").ok());
        assertFalse(loadWith("territories.bonuses.hard-cap", 0.5).ok());
        assertFalse(loadWith("territories.bonuses.stacking", "UNLIMITED").ok());
        assertFalse(loadWith("storage.type", "POSTGRES").ok());
        assertFalse(loadWith("storage.table-prefix", "drt; DROP TABLE x").ok());
        assertFalse(loadWith("storage.sqlite-file", "../evil.db").ok());
    }

    @Test
    @DisplayName("Contradictory or unsafe settings are rejected")
    void contradictions() {
        assertFalse(loadWith("loot-keys.faction-policy.prevent-same-faction-kills", false).ok());
        assertFalse(loadWith("territories.protection.respect-factionsuuid", false).ok());
        YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("territories.capture.contested-behavior", "DECAY");
        yaml.set("territories.capture.decay-per-second", 0);
        assertFalse(SettingsLoader.load(yaml).ok());
        assertFalse(loadWith("progression.absolute-max-territories", 1).ok());
        assertFalse(loadWith("progression.levels", List.of(Map.of("xp", 100), Map.of("xp", 50))).ok());
        YamlConfiguration overrides = Fixtures.bundled("config.yml");
        overrides.createSection("loot-keys.faction-policy.territory-overrides", Map.of("docks", "MAYBE"));
        assertFalse(SettingsLoader.load(overrides).ok());
        assertFalse(loadWith("config-version", 99).ok());
        YamlConfiguration longCapture = Fixtures.bundled("config.yml");
        longCapture.set("territories.capture.duration-seconds", 7_200);
        longCapture.set("territories.capture.ownership-duration-hours", 1);
        assertFalse(SettingsLoader.load(longCapture).ok(), "a capture may not outlast the ownership it wins");
    }

    @Test
    @DisplayName("Level unlocks add territory slots")
    void levels() {
        Settings.Progression p = Fixtures.settings().progression();
        assertEquals(1, p.levelFor(0).number());
        assertEquals(3, p.levelFor(2500).number());
        assertEquals(1, p.extraSlotsFor(2500));
        assertEquals(2, p.extraSlotsFor(1_000_000));
    }

    @Test
    @DisplayName("Storage settings never print the password")
    void passwordHidden() {
        YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("storage.type", "MYSQL");
        yaml.set("storage.password", "hunter2");
        Settings s = SettingsLoader.load(yaml).settings();
        assertFalse(s.storage().toString().contains("hunter2"));
    }
}
