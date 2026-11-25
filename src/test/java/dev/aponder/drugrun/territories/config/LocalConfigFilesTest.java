/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.support.Fixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalConfigFilesTest {

    @TempDir
    Path dir;

    private static Function<String, InputStream> resources(Map<String, String> files) {
        return name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name).getBytes(StandardCharsets.UTF_8)) : null;
    }

    @Test
    @DisplayName("Creates the file from the bundled default when missing")
    void createsDefault() throws IOException {
        LocalConfigFiles files = new LocalConfigFiles(dir, resources(Map.of("a.yml", "x: 1\n")), Fixtures.LOG);
        assertEquals(1, files.load("a.yml").getInt("x"));
        assertTrue(Files.exists(dir.resolve("a.yml")));
    }

    @Test
    @DisplayName("Adds new default keys, keeps administrator values, and writes a backup first")
    void mergesWithoutOverwriting() throws IOException {
        Files.writeString(dir.resolve("a.yml"), "x: 99\n");
        LocalConfigFiles files = new LocalConfigFiles(dir, resources(Map.of("a.yml", "x: 1\ny: 2\n")), Fixtures.LOG);
        YamlConfiguration yaml = files.load("a.yml");
        assertEquals(99, yaml.getInt("x"));
        assertEquals(2, yaml.getInt("y"));
        assertTrue(Files.list(dir.resolve("backups")).findAny().isPresent());
        assertTrue(Files.readString(dir.resolve("a.yml")).contains("y: 2"));
    }

    @Test
    @DisplayName("Data files are never refilled with deleted default entries")
    void noMergeForDataFiles() throws IOException {
        Files.writeString(dir.resolve("t.yml"), "territories: {}\n");
        LocalConfigFiles files = new LocalConfigFiles(dir, resources(Map.of("t.yml", "territories:\n  docks:\n    world: w\n")), Fixtures.LOG);
        assertFalse(files.load("t.yml", false).contains("territories.docks"));
    }

    @Test
    @DisplayName("Corrupt YAML is reported, not silently replaced")
    void corrupt() throws IOException {
        Files.writeString(dir.resolve("a.yml"), "x: [unclosed\n");
        LocalConfigFiles files = new LocalConfigFiles(dir, resources(Map.of("a.yml", "x: 1\n")), Fixtures.LOG);
        assertThrows(IOException.class, () -> files.load("a.yml"));
        assertEquals("x: [unclosed\n", Files.readString(dir.resolve("a.yml")));
    }

    @Test
    @DisplayName("Saves atomically and refuses paths outside the data folder")
    void atomicSaveAndTraversal() throws IOException {
        LocalConfigFiles files = new LocalConfigFiles(dir, resources(Map.of()), Fixtures.LOG);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("k", "v");
        files.save("b.yml", yaml);
        assertEquals("v", files.load("b.yml").getString("k"));
        assertFalse(Files.exists(dir.resolve("b.yml.tmp")));
        assertThrows(IOException.class, () -> files.save("../escape.yml", yaml));
    }
}
