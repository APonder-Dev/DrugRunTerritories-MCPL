/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.lootkey;

import dev.aponder.drugrun.territories.api.FactionRelation;
import dev.aponder.drugrun.territories.api.KillDecision;
import dev.aponder.drugrun.territories.api.KillDecision.Outcome;
import dev.aponder.drugrun.territories.api.KillDecision.Reason;
import dev.aponder.drugrun.territories.api.LootKeyFactionPolicy;
import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;

/**
 * Faction rules for Loot Key eligibility. Evaluation order (first match wins):
 * <ol>
 *   <li>Self-kill: deny.</li>
 *   <li>Faction data unavailable: UNAVAILABLE (callers treat as deny).</li>
 *   <li>Same faction: deny (always; cannot be configured off).</li>
 *   <li>Ally, or truce when truce counts as ally: deny when configured.</li>
 *   <li>Territory override for the death location: forced ALLOW or DENY. Overrides never
 *       re-enable same-faction or allied kills.</li>
 *   <li>Safezone: deny when configured.</li>
 *   <li>Enemy factions: allow when configured.</li>
 *   <li>Warzone: allow when configured.</li>
 *   <li>Wilderness: allow when configured.</li>
 *   <li>Everything else (neutral factions, factionless players in claimed land): allow-neutral-kills.</li>
 * </ol>
 * DrugRunCore remains responsible for everything about the key itself.
 */
public final class FactionKillPolicy implements LootKeyFactionPolicy {

    private final FactionProvider factions;
    private final Supplier<KillPolicySettings> settings;
    private final Supplier<TerritoryRegistry> registry;

    public FactionKillPolicy(FactionProvider factions, Supplier<KillPolicySettings> settings, Supplier<TerritoryRegistry> registry) {
        this.factions = factions;
        this.settings = settings;
        this.registry = registry;
    }

    @Override
    public KillDecision evaluate(UUID killer, UUID victim, Location deathLocation) {
        if (killer.equals(victim)) {
            return new KillDecision(Outcome.DENY, Reason.SELF_KILL, FactionRelation.SAME_FACTION);
        }
        KillPolicySettings cfg = settings.get();
        FactionRelation relation;
        FactionRef land;
        try {
            relation = factions.relation(killer, victim);
            land = factions.factionAt(deathLocation);
        } catch (FactionLookupException e) {
            return new KillDecision(Outcome.UNAVAILABLE, Reason.FACTIONS_UNAVAILABLE, FactionRelation.NONE);
        }

        if (relation == FactionRelation.SAME_FACTION && cfg.preventSameFaction()) {
            return new KillDecision(Outcome.DENY, Reason.SAME_FACTION, relation);
        }
        boolean allied = relation == FactionRelation.ALLY || (relation == FactionRelation.TRUCE && cfg.truceCountsAsAlly());
        if (allied && cfg.preventAllied()) {
            return new KillDecision(Outcome.DENY, Reason.ALLIED_FACTION, relation);
        }
        Optional<TerritoryDefinition> territory = registry.get().at(deathLocation.getWorld().getName(),
                deathLocation.getBlockX(), deathLocation.getBlockY(), deathLocation.getBlockZ());
        if (territory.isPresent()) {
            Outcome forced = cfg.territoryOverrides().get(territory.get().id());
            if (forced != null) {
                return new KillDecision(forced, Reason.TERRITORY_OVERRIDE, relation);
            }
        }
        if (land.kind() == FactionRef.Kind.SAFEZONE && cfg.denyInSafezones()) {
            return new KillDecision(Outcome.DENY, Reason.SAFEZONE, relation);
        }
        if (relation == FactionRelation.ENEMY) {
            return decide(cfg.allowEnemy(), Reason.ENEMY_FACTION, relation);
        }
        if (land.kind() == FactionRef.Kind.WARZONE) {
            return decide(cfg.allowWarzone(), Reason.WARZONE, relation);
        }
        if (land.kind() == FactionRef.Kind.WILDERNESS) {
            return decide(cfg.allowWilderness(), Reason.WILDERNESS, relation);
        }
        return decide(cfg.allowNeutral(), Reason.NEUTRAL, relation);
    }

    private static KillDecision decide(boolean allow, Reason reason, FactionRelation relation) {
        return new KillDecision(allow ? Outcome.ALLOW : Outcome.DENY, reason, relation);
    }

    @Override
    public FactionRelation relation(UUID first, UUID second) {
        try {
            return factions.relation(first, second);
        } catch (FactionLookupException e) {
            return FactionRelation.NONE;
        }
    }

    @Override
    public RaidState raidStateAt(Location location) {
        try {
            return factions.raidStateAt(location);
        } catch (FactionLookupException e) {
            // Fail closed: report the most protective state.
            return RaidState.SERVER_PROTECTED;
        }
    }
}
