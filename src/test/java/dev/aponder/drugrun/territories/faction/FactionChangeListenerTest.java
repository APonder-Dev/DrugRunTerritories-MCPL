/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Claim changes and disbands coming from FactionsUUID (spec tests 6 and 7). */
class FactionChangeListenerTest {

    private final FakeFactions factions = new FakeFactions();
    private final List<String> cleaned = new ArrayList<>();

    private FactionChangeListener listener(TerritoryService service) {
        return new FactionChangeListener(service, cleaned::add);
    }

    private TerritoryService service() {
        TerritoryRepository repo = mock(TerritoryRepository.class);
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        ProgressionService progression = mock(ProgressionService.class);
        TerritoryService s = new TerritoryService(Fixtures.settings(),
                new TerritoryRegistry(List.of(Fixtures.territory("docks", 1000, 1000, Map.of()))), repo, factions, progression,
                (d, n) -> Map.of(), Runnable::run, System::currentTimeMillis, () -> LocalTime.NOON, Fixtures.LOG);
        s.initialize(Map.of());
        return s;
    }

    @Test
    @DisplayName("7: claiming a chunk that touches a territory is cancelled; other claims pass")
    void claimsInsideTerritoriesBlocked() {
        FactionChangeListener l = listener(service());
        Faction f = mock(Faction.class);
        LandClaimEvent inside = new LandClaimEvent(new FLocation("world", 1000 >> 4, 1000 >> 4), f, null);
        l.onClaim(inside);
        assertTrue(inside.isCancelled());
        LandClaimEvent outside = new LandClaimEvent(new FLocation("world", 0, 0), f, null);
        l.onClaim(outside);
        assertFalse(outside.isCancelled());
        LandClaimEvent otherWorld = new LandClaimEvent(new FLocation("nether", 1000 >> 4, 1000 >> 4), f, null);
        l.onClaim(otherWorld);
        assertFalse(otherWorld.isCancelled());
    }

    @Test
    @DisplayName("6: a disband releases the faction's territory and triggers cleanup")
    void disband() {
        factions.add("5", "Echo");
        TerritoryService service = service();
        service.forceCapture("docks", "5");
        Faction f = mock(Faction.class);
        when(f.id()).thenReturn(5);
        listener(service).onDisband(new FactionDisbandEvent(null, f));
        assertFalse(service.state("docks").orElseThrow().owned());
        assertEquals(List.of("5"), cleaned);
    }
}
