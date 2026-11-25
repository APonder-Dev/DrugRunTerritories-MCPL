/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.economy;

import java.util.UUID;

/** Minimal money interface. Main thread only. */
public interface EconomyGateway {

    /** @return whether an economy provider is ready */
    boolean available();

    /**
     * Deposits money.
     *
     * @return whether the provider reports success
     */
    boolean deposit(UUID player, double amount);

    /** @return the amount formatted by the economy provider */
    String format(double amount);

    /** Gateway used when no economy plugin is installed. Every deposit fails. */
    EconomyGateway NONE = new EconomyGateway() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean deposit(UUID player, double amount) {
            return false;
        }

        @Override
        public String format(double amount) {
            return String.format(java.util.Locale.ROOT, "%.2f", amount);
        }
    };
}
