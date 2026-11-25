/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.ui;

import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import java.time.Duration;
import java.util.Locale;

/** Display helpers shared by messages, BossBars, commands and placeholders. */
public final class FactionNames {

    private final FactionProvider factions;

    public FactionNames(FactionProvider factions) {
        this.factions = factions;
    }

    /** @return the faction's current tag, {@code "-"} for none, {@code "?"} when it cannot be read */
    public String name(String factionId) {
        if (factionId == null) {
            return "-";
        }
        try {
            return factions.byId(factionId).map(FactionRef::name).orElse("(disbanded)");
        } catch (FactionLookupException e) {
            return "?";
        }
    }

    /** @return {@code 1h 05m}, {@code 4m 09s} or {@code 12s} */
    public static String duration(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, millis));
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", h, m);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", m, s);
        }
        return s + "s";
    }

    /** @return {@code 0} to {@code 100} */
    public static int percent(double part, double whole) {
        if (!(whole > 0)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, Math.floor(part / whole * 100)));
    }
}
