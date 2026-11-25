/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.api;

import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/** Implementation of {@link DrugRunTerritoriesApi}. */
public final class ApiImpl implements DrugRunTerritoriesApi {

    private final TerritoryService territories;
    private final ProgressionService progression;
    private final FactionProvider factions;
    private final LootKeyFactionPolicy policy;

    public ApiImpl(TerritoryService territories, ProgressionService progression, FactionProvider factions, LootKeyFactionPolicy policy) {
        this.territories = territories;
        this.progression = progression;
        this.factions = factions;
        this.policy = policy;
    }

    @Override
    public List<TerritoryInfo> territories() {
        return territories.registry().enabled().stream().map(this::info).toList();
    }

    @Override
    public Optional<TerritoryInfo> territoryAt(Location location) {
        return territories.registry().at(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ()).map(this::info);
    }

    @Override
    public double bonusMultiplier(UUID player, String bonusType) {
        BonusType type;
        try {
            type = BonusType.valueOf(bonusType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return 1.0;
        }
        try {
            return factions.factionOf(player).map(f -> territories.bonusMultiplier(f.id(), type)).orElse(1.0);
        } catch (FactionLookupException e) {
            return 1.0;
        }
    }

    @Override
    public long factionXp(String factionId) {
        return progression.get(factionId).xp();
    }

    @Override
    public long factionReputation(String factionId) {
        return progression.get(factionId).reputation();
    }

    @Override
    public LootKeyFactionPolicy lootKeyPolicy() {
        return policy;
    }

    private TerritoryInfo info(TerritoryDefinition def) {
        TerritoryState s = territories.state(def.id()).orElse(TerritoryState.available(def.id()));
        double duration = def.rules(territories.settings().capture()).durationSeconds();
        return new TerritoryInfo(def.id(), def.displayName(), def.type().name(), s.state().name(), s.ownerFactionId(),
                s.capturingFactionId(), duration > 0 ? Math.min(1.0, s.progressSeconds() / duration) : 0, s.ownedUntil());
    }
}
