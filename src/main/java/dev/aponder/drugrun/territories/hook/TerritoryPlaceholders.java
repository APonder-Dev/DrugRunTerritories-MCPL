/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.hook;

import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.FactionProgress;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import dev.aponder.drugrun.territories.ui.FactionNames;
import dev.aponder.drugrun.territories.ui.Messages;
import java.util.Locale;
import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI expansion, identifier {@code drugrun}. Every placeholder listed here is
 * implemented; see COMMANDS.md for the full list.
 *
 * <pre>
 * %drugrun_territory_name%       territory the player stands in (plain text), "-" when none
 * %drugrun_territory_owner%      its controlling faction, "-" when none
 * %drugrun_territory_status%     AVAILABLE, CAPTURING, CONTESTED, CONTROLLED, COOLDOWN
 * %drugrun_territory_progress%   capture progress 0-100
 * %drugrun_territory_&lt;id&gt;_name|owner|status|progress%   the same for a named territory
 * %drugrun_faction_xp%  %drugrun_faction_level%  %drugrun_faction_reputation%
 * %drugrun_faction_territories%  number of territories the player's faction controls
 * %drugrun_faction_income%       total territory income the faction has received
 * %drugrun_faction_captures%  %drugrun_faction_sales% (items sold)  %drugrun_faction_processed%
 * </pre>
 */
public final class TerritoryPlaceholders extends PlaceholderExpansion {

    private final Plugin plugin;
    private final TerritoryService territories;
    private final ProgressionService progression;
    private final FactionProvider factions;
    private final FactionNames names;

    public TerritoryPlaceholders(Plugin plugin, TerritoryService territories, ProgressionService progression,
                                 FactionProvider factions, FactionNames names) {
        this.plugin = plugin;
        this.territories = territories;
        this.progression = progression;
        this.factions = factions;
        this.names = names;
    }

    @Override
    public String getIdentifier() {
        return "drugrun";
    }

    @Override
    public String getAuthor() {
        return "Anthony Ponder / APonder.dev";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("faction_")) {
            return faction(player, p.substring("faction_".length()));
        }
        if (p.startsWith("territory_")) {
            String rest = p.substring("territory_".length());
            if (rest.equals("name") || rest.equals("owner") || rest.equals("status") || rest.equals("progress")) {
                Optional<TerritoryDefinition> here = player instanceof Player online ? at(online.getLocation()) : Optional.empty();
                return here.map(def -> territory(def, rest)).orElse("-");
            }
            int split = rest.lastIndexOf('_');
            if (split > 0) {
                String id = rest.substring(0, split);
                String field = rest.substring(split + 1);
                return territories.registry().get(id).map(def -> territory(def, field)).orElse(null);
            }
        }
        return null;
    }

    private String territory(TerritoryDefinition def, String field) {
        TerritoryState s = territories.state(def.id()).orElse(TerritoryState.available(def.id()));
        return switch (field) {
            case "name" -> Messages.plain(def.displayName());
            case "owner" -> names.name(s.ownerFactionId());
            case "status" -> s.state().name();
            case "progress" -> String.valueOf(FactionNames.percent(s.progressSeconds(),
                    def.rules(territories.settings().capture()).durationSeconds()));
            default -> null;
        };
    }

    private String faction(OfflinePlayer player, String field) {
        Optional<FactionRef> faction;
        try {
            faction = factions.factionOf(player.getUniqueId());
        } catch (FactionLookupException e) {
            return "?";
        }
        if (faction.isEmpty()) {
            return field.equals("territories") || field.equals("xp") || field.equals("reputation") || field.equals("captures")
                    || field.equals("sales") || field.equals("processed") ? "0"
                    : "-";
        }
        String id = faction.get().id();
        FactionProgress progress = progression.get(id);
        return switch (field) {
            case "xp" -> String.valueOf(progress.xp());
            case "level" -> String.valueOf(progression.level(id).number());
            case "reputation" -> String.valueOf(progress.reputation());
            case "territories" -> String.valueOf(territories.ownedBy(id).size());
            case "income" -> String.format(Locale.ROOT, "%.2f", progress.incomeEarned());
            case "captures" -> String.valueOf(progress.captures());
            case "sales" -> String.valueOf(progress.itemsSold());
            case "processed" -> String.valueOf(progress.jobsProcessed());
            default -> null;
        };
    }

    private Optional<TerritoryDefinition> at(Location l) {
        return territories.registry().at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
