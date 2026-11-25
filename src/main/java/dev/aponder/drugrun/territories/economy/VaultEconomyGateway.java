/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.economy;

import java.util.Locale;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** {@link EconomyGateway} backed by Vault. Only constructed when Vault is installed. */
public final class VaultEconomyGateway implements EconomyGateway {

    private Economy economy;

    /** @return whether a Vault economy provider was found */
    public boolean hook() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
        return economy != null;
    }

    @Override
    public boolean available() {
        return economy != null && economy.isEnabled();
    }

    @Override
    public boolean deposit(UUID player, double amount) {
        if (!available() || !(amount > 0) || Double.isInfinite(amount)) {
            return false;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(player);
        EconomyResponse response = economy.depositPlayer(target, amount);
        return response != null && response.transactionSuccess();
    }

    @Override
    public String format(double amount) {
        return available() ? economy.format(amount) : String.format(Locale.ROOT, "%.2f", amount);
    }
}
