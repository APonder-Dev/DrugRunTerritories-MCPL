/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.lootkey;

import dev.aponder.drugrun.territories.api.KillDecision;
import java.util.Map;

/**
 * Faction rules for which PvP kills may earn a DrugRunCore Loot Key ({@code loot-keys.faction-policy}).
 *
 * @param territoryOverrides territory id to forced decision; applies to every kill inside that territory
 */
public record KillPolicySettings(
        boolean preventSameFaction,
        boolean preventAllied,
        boolean truceCountsAsAlly,
        boolean allowEnemy,
        boolean allowNeutral,
        boolean allowWilderness,
        boolean allowWarzone,
        boolean denyInSafezones,
        Map<String, KillDecision.Outcome> territoryOverrides) {
}
