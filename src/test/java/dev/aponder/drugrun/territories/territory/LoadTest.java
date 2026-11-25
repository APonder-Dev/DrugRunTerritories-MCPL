/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Throughput of the capture loop (spec tests 66 and 68, logic only; not a substitute for Spark profiling on a live server). */
class LoadTest {

    @Test
    @DisplayName("68: 200 territories with 40 factions contesting them tick 610 times in well under the tick budget")
    void manyTerritoriesAndFactions() {
        FakeFactions factions = new FakeFactions();
        for (int i = 0; i < 40; i++) {
            factions.add(String.valueOf(i), "F" + i);
        }
        List<TerritoryDefinition> defs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            defs.add(Fixtures.territory("t" + i, (i % 20) * 64, (i / 20) * 64, Map.of()));
        }
        Map<String, Map<String, Integer>> presence = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            presence.put("t" + i, i % 3 == 0 ? Map.of(String.valueOf(i % 40), 3) : Map.of(String.valueOf(i % 40), 2, String.valueOf((i + 1) % 40), 1));
        }
        TerritoryRepository repo = mock(TerritoryRepository.class);
        when(repo.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        ProgressionService progression = mock(ProgressionService.class);
        when(progression.territoryLimit(any())).thenReturn(100);
        when(progression.settings()).thenReturn(Fixtures.settings().progression());
        AtomicLong clock = new AtomicLong(0);
        TerritoryService service = new TerritoryService(Fixtures.settings(), new TerritoryRegistry(defs), repo, factions, progression,
                (d, n) -> presence.get(d.id()), Runnable::run, clock::get, () -> LocalTime.NOON, Fixtures.LOG);
        service.initialize(Map.of());
        long start = System.nanoTime();
        for (int i = 0; i < 610; i++) {
            clock.addAndGet(1000);
            service.tick();
        }
        double perTickMs = (System.nanoTime() - start) / 1e6 / 610;
        assertTrue(perTickMs < 5.0, "average capture tick took " + perTickMs + " ms for 200 territories");
        assertTrue(service.ownedBy("0").size() + service.ownedBy("3").size() > 0, "uncontested territories were captured");
    }
}
