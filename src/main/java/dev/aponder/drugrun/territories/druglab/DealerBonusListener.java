/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import dev.aponder.druglab.api.DrugLabAPI;
import dev.aponder.druglab.api.event.ShopTransactionEvent;
import dev.aponder.druglab.economy.ShopEntry;
import dev.aponder.druglab.stats.Stat;
import dev.aponder.drugrun.territories.bonus.BonusCalculator;
import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRank;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Applies the DEALER_SELL territory bonus to DrugLab dealer sales.
 *
 * <p>DrugLab computes the sale price (quality tier, supply and demand, daily request, boosters,
 * capped at the dealer's buy price) and fires {@code ShopTransactionEvent} before paying. This
 * listener multiplies that finished price, so every DrugLab modifier is preserved. DrugLab makes
 * the single Vault deposit with the event's final price; nothing here deposits money, so a
 * cancelled or failed sale pays nothing extra.
 *
 * <p>Safeguards: only SELL transactions; only members of a faction that controls a DEALER_SELL
 * territory; rank and world requirements; stacking rules and hard cap; a per-transaction bonus
 * cap; and the result never exceeds what the dealer would charge to buy the same items back (the
 * same rule DrugLab uses), so buy-and-resell loops stay unprofitable.
 */
final class DealerBonusListener implements Listener {

    private final FactionProvider factions;
    private final TerritoryService territories;
    private final Supplier<Settings> settings;
    private final Logger logger;

    DealerBonusListener(FactionProvider factions, TerritoryService territories, Supplier<Settings> settings, Logger logger) {
        this.factions = factions;
        this.territories = territories;
        this.settings = settings;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransaction(ShopTransactionEvent event) {
        if (event.getType() != ShopTransactionEvent.Type.SELL) {
            return;
        }
        double price = event.getPrice();
        if (!(price > 0) || Double.isInfinite(price) || event.getAmount() <= 0) {
            return;
        }
        Player player = event.getPlayer();
        Settings.Bonuses cfg = settings.get().bonuses();
        if (!cfg.dealerAllowedWorlds().isEmpty() && !cfg.dealerAllowedWorlds().contains(player.getWorld().getName())) {
            return;
        }
        Optional<FactionRef> faction;
        Optional<FactionRank> rank;
        try {
            faction = factions.factionOf(player.getUniqueId());
            rank = factions.rankOf(player.getUniqueId());
        } catch (FactionLookupException e) {
            return; // fail closed: no bonus
        }
        if (faction.isEmpty() || rank.isEmpty() || !rank.get().atLeast(cfg.dealerMinimumRank())) {
            return;
        }
        double multiplier = territories.bonusMultiplier(faction.get().id(), BonusType.DEALER_SELL);
        if (multiplier <= 1.0) {
            return;
        }
        double boosted = BonusCalculator.applyToPrice(price, multiplier, cfg.dealerMaxBonusPerTransaction());
        boosted = Math.min(boosted, buyBackCeiling(event.getEntry(), event.getAmount(), player, boosted));
        if (boosted > price) {
            event.setPrice(boosted);
            if (settings.get().debug()) {
                logger.info(String.format(Locale.ROOT, "Dealer bonus for %s (%s): %.2f -> %.2f (x%.3f)",
                        player.getName(), faction.get().name(), price, boosted, multiplier));
            }
        }
    }

    /**
     * DrugLab never pays more for an item than the dealer charges for it. Reproduces that ceiling
     * with public DrugLab API: buy price per item times amount times the player's BUY_PRICE booster.
     */
    private static double buyBackCeiling(ShopEntry entry, int amount, Player player, double fallback) {
        if (entry == null || !entry.canBuy()) {
            return fallback;
        }
        return entry.buyPrice() * amount * DrugLabAPI.getMultiplier(player.getUniqueId(), Stat.BUY_PRICE);
    }
}
