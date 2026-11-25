/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Territory definitions, YAML round trips and the spatial index (spec tests 11, 12, 66). */
class TerritoryDefinitionTest {

    @Test
    @DisplayName("Bundled territories.yml defines the four districts, disabled until placed")
    void bundled() {
        TerritoryDefinitionCodec.Result r = TerritoryDefinitionCodec.read(Fixtures.bundled("territories.yml"));
        assertTrue(r.ok(), r.errors().toString());
        assertEquals(List.of("docks", "industrial", "greenhouse", "downtown"), r.definitions().stream().map(TerritoryDefinition::id).toList());
        assertTrue(r.definitions().stream().noneMatch(TerritoryDefinition::enabled));
        TerritoryDefinition docks = r.definitions().getFirst();
        assertEquals(1.10, docks.bonuses().get(BonusType.DEALER_SELL), 1e-9);
        TerritoryDefinition downtown = r.definitions().getLast();
        assertEquals(250.0, downtown.income().amount(), 1e-9);
        assertEquals(Duration.ofMinutes(60), downtown.income().interval());
    }

    @Test
    @DisplayName("11/12: create, write, read back and delete a definition")
    void roundTrip() {
        TerritoryDefinition def = Fixtures.withIncome(Fixtures.territory("port", 0, 0, Map.of(BonusType.CROP_GROWTH, 1.2)), 100, Duration.ofMinutes(30), 5);
        YamlConfiguration yaml = new YamlConfiguration();
        TerritoryDefinitionCodec.write(yaml, def);
        TerritoryDefinitionCodec.Result back = TerritoryDefinitionCodec.read(yaml);
        assertTrue(back.ok(), back.errors().toString());
        TerritoryDefinition read = back.definitions().getFirst();
        assertEquals(def.region(), read.region());
        assertEquals(def.captureZone(), read.captureZone());
        assertEquals(def.bonuses(), read.bonuses());
        assertEquals(def.income(), read.income());
        yaml.set("territories.port", null);
        assertTrue(TerritoryDefinitionCodec.read(yaml).definitions().isEmpty());
    }

    @Test
    @DisplayName("Overlapping enabled regions and capture points outside the region are rejected")
    void validation() {
        YamlConfiguration yaml = new YamlConfiguration();
        TerritoryDefinitionCodec.write(yaml, Fixtures.territory("a", 0, 0, Map.of()));
        TerritoryDefinitionCodec.write(yaml, Fixtures.territory("b", 16, 16, Map.of()));
        assertFalse(TerritoryDefinitionCodec.read(yaml).ok());
        yaml.set("territories.b.enabled", false);
        assertTrue(TerritoryDefinitionCodec.read(yaml).ok(), "disabled territories may overlap");
        yaml.set("territories.a.capture.center", List.of(500, 64, 500));
        assertFalse(TerritoryDefinitionCodec.read(yaml).ok());
        yaml.set("territories.a.capture.center", List.of(10, 64, 10));
        yaml.set("territories.a.bonuses.DEALER_SELL", 50.0);
        assertFalse(TerritoryDefinitionCodec.read(yaml).ok());
    }

    @Test
    @DisplayName("Ids, radius and region corners are validated")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.territory("Bad Id!", 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CaptureZone(0, 0, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Region("w", 5, 0, 0, 4, 0, 0));
        Region r = Region.of("w", 10, 5, 10, -10, 0, -10);
        assertEquals(-10, r.minX());
        assertEquals(10, r.maxZ());
    }

    @Test
    @DisplayName("Registry lookups respect exact bounds across chunk borders")
    void registryLookup() {
        TerritoryRegistry reg = new TerritoryRegistry(List.of(Fixtures.territory("a", 8, 8, Map.of())));
        assertTrue(reg.at("world", 8, 0, 8).isPresent());
        assertTrue(reg.at("world", 39, 255, 39).isPresent());
        assertFalse(reg.at("world", 40, 64, 40).isPresent());
        assertFalse(reg.at("world", 7, 64, 20).isPresent());
        assertFalse(reg.at("nether", 20, 64, 20).isPresent());
        assertTrue(reg.touchesChunk("world", 0, 0));
        assertTrue(reg.touchesChunk("world", 2, 2));
        assertFalse(reg.touchesChunk("world", 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new TerritoryRegistry(List.of(Fixtures.territory("a", 0, 0, Map.of()),
                Fixtures.territory("a", 100, 100, Map.of()))));
    }

    @Test
    @DisplayName("66: a thousand territories stay fast to index and query")
    void manyTerritories() {
        List<TerritoryDefinition> defs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            defs.add(Fixtures.territory("t" + i, (i % 40) * 64, (i / 40) * 64, Map.of()));
        }
        long start = System.nanoTime();
        TerritoryRegistry reg = new TerritoryRegistry(defs);
        int hits = 0;
        for (int i = 0; i < 200_000; i++) {
            if (reg.at("world", (i * 37) % 2560, 64, (i * 91) % 1600).isPresent()) {
                hits++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(hits > 0);
        assertTrue(millis < 2000, "indexing 1000 territories and 200k lookups took " + millis + " ms");
    }

    @Test
    @DisplayName("Capture schedules, including windows across midnight")
    void schedule() {
        TimeWindow evening = TimeWindow.parse("18:00-22:00");
        assertTrue(evening.contains(LocalTime.of(19, 0)));
        assertFalse(evening.contains(LocalTime.of(22, 0)));
        TimeWindow night = TimeWindow.parse("22:00-02:00");
        assertTrue(night.contains(LocalTime.of(23, 30)));
        assertTrue(night.contains(LocalTime.of(1, 0)));
        assertFalse(night.contains(LocalTime.of(3, 0)));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("noon"));
    }
}
