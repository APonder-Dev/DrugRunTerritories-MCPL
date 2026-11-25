/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.economy;

import dev.aponder.drugrun.territories.audit.AuditLog;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.faction.FactionLookupException;
import dev.aponder.drugrun.territories.faction.FactionProvider;
import dev.aponder.drugrun.territories.faction.FactionRef;
import dev.aponder.drugrun.territories.progression.ProgressDelta;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.territory.CaptureState;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Recurring territory income and one-off capture money rewards.
 *
 * <p>Every payout follows the same steps:
 * <ol>
 *   <li>Claim a unique key ({@code territory:ownership:period}) in the ledger. If the key exists,
 *       stop: it was paid, failed, or is in doubt, and is never paid twice.</li>
 *   <li>Back on the main thread, re-check that the same ownership still exists, that the period
 *       ended before ownership expired, and that the faction still exists.</li>
 *   <li>Deposit through the economy provider.</li>
 *   <li>Mark the row PAID or FAILED and write an audit entry.</li>
 * </ol>
 * A crash between steps 1 and 4 leaves the row PENDING; on the next start it becomes UNKNOWN and
 * is reported, never retried.
 */
public final class IncomeService {

    /** Period index used for the one-off capture reward. Income periods start at 1. */
    public static final int CAPTURE_REWARD_PERIOD = 0;

    private final TerritoryService territories;
    private final FactionProvider factions;
    private final ProgressionService progression;
    private final PayoutRepository payouts;
    private final AuditLog audit;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Logger logger;
    private EconomyGateway economy;
    private Settings.Income settings;

    /** ownership id -> highest period index already claimed */
    private final Map<String, Integer> claimed = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();

    public IncomeService(TerritoryService territories, FactionProvider factions, ProgressionService progression,
                         PayoutRepository payouts, AuditLog audit, EconomyGateway economy, Settings.Income settings,
                         Executor mainThread, LongSupplier clock, Logger logger) {
        this.territories = territories;
        this.factions = factions;
        this.progression = progression;
        this.payouts = payouts;
        this.audit = audit;
        this.economy = economy;
        this.settings = settings;
        this.mainThread = mainThread;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Startup: marks interrupted payouts UNKNOWN, loads claimed periods, and (unless
     * {@code pay-missed-periods}) skips periods that ended while the server was offline.
     */
    public void initialize(Map<String, Integer> claimedPeriods) {
        claimed.clear();
        claimed.putAll(claimedPeriods);
        if (settings.payMissedPeriods()) {
            return;
        }
        long now = clock.getAsLong();
        for (TerritoryDefinition def : territories.registry().enabled()) {
            TerritoryState s = territories.state(def.id()).orElse(null);
            if (def.income() == null || s == null || !s.owned() || s.ownershipId() == null) {
                continue;
            }
            int current = periodIndex(s, def.income(), Math.min(now, s.ownedUntil()));
            claimed.merge(s.ownershipId().toString(), current, Math::max);
        }
    }

    public void reconfigure(Settings.Income newSettings, EconomyGateway newEconomy) {
        this.settings = newSettings;
        this.economy = newEconomy;
    }

    /** Checks every income territory and pays periods that have completed. Main thread. */
    public void tick() {
        long now = clock.getAsLong();
        for (TerritoryDefinition def : territories.registry().enabled()) {
            TerritoryDefinition.Income income = def.income();
            TerritoryState s = territories.state(def.id()).orElse(null);
            if (income == null || s == null || !s.owned() || s.ownershipId() == null) {
                continue;
            }
            int due = periodIndex(s, income, Math.min(now, s.ownedUntil()));
            if (income.maxPayoutsPerOwnership() > 0) {
                due = Math.min(due, income.maxPayoutsPerOwnership());
            }
            String ownership = s.ownershipId().toString();
            int last = claimed.getOrDefault(ownership, 0);
            for (int period = last + 1; period <= due; period++) {
                claimed.put(ownership, period);
                pay(def, s, period, income.amount(), "income");
            }
        }
    }

    /** Pays a territory's one-off capture money reward, once per ownership. */
    public void payCaptureReward(TerritoryDefinition def, TerritoryState state) {
        double money = def.rewards().money();
        if (money > 0 && state.owned() && state.ownershipId() != null) {
            pay(def, state, CAPTURE_REWARD_PERIOD, money, "capture-reward");
        }
    }

    static int periodIndex(TerritoryState s, TerritoryDefinition.Income income, long at) {
        long elapsed = at - s.ownerSince();
        if (elapsed <= 0) {
            return 0;
        }
        long index = elapsed / income.interval().toMillis();
        return (int) Math.min(index, Integer.MAX_VALUE);
    }

    private void pay(TerritoryDefinition def, TerritoryState snapshot, int period, double amount, String kind) {
        String ownership = snapshot.ownershipId().toString();
        String key = def.id() + ":" + ownership + ":" + period;
        if (!inFlight.add(key)) {
            return;
        }
        String factionId = snapshot.ownerFactionId();
        payouts.claim(key, def.id(), ownership, period, factionId, amount).whenComplete((won, err) -> mainThread.execute(() -> {
            if (err != null) {
                inFlight.remove(key);
                logger.log(Level.SEVERE, "Could not claim payout " + key + "; it will not be paid", err);
                return;
            }
            if (!won) {
                inFlight.remove(key);
                return;
            }
            deliver(def, key, period, factionId, ownership, amount, kind);
        }));
    }

    private void deliver(TerritoryDefinition def, String key, int period, String factionId, String ownership, double amount,
                         String kind) {
        try {
            String failure = validate(def, period, factionId, ownership);
            List<UUID> recipients = failure == null ? recipients(factionId) : List.of();
            if (failure == null && recipients.isEmpty()) {
                failure = "no recipient available";
            }
            if (failure == null && !economy.available()) {
                failure = "economy unavailable";
            }
            if (failure != null) {
                finish(key, PayoutRepository.Status.FAILED, failure, factionId, amount, kind);
                return;
            }
            double share = amount / recipients.size();
            List<UUID> paid = new ArrayList<>();
            for (UUID r : recipients) {
                if (economy.deposit(r, share)) {
                    paid.add(r);
                }
            }
            if (paid.isEmpty()) {
                finish(key, PayoutRepository.Status.FAILED, "economy refused every deposit", factionId, amount, kind);
                return;
            }
            double total = share * paid.size();
            String detail = "paid " + String.format(Locale.ROOT, "%.2f", total) + " to " + paid.size() + " recipient(s)"
                    + (paid.size() < recipients.size() ? ", " + (recipients.size() - paid.size()) + " deposit(s) refused" : "");
            progression.add(factionId, ProgressDelta.income(total));
            finish(key, PayoutRepository.Status.PAID, detail, factionId, total, kind);
        } catch (FactionLookupException e) {
            finish(key, PayoutRepository.Status.FAILED, "faction lookup failed: " + e.getMessage(), factionId, amount, kind);
        }
    }

    /** @return {@code null} when the payout is still valid, otherwise why not */
    private String validate(TerritoryDefinition def, int period, String factionId, String ownership) {
        Optional<TerritoryState> current = territories.state(def.id());
        if (current.isEmpty()) {
            return "territory no longer exists";
        }
        TerritoryState s = current.get();
        boolean sameOwnership = s.ownershipId() != null && s.ownershipId().toString().equals(ownership)
                && factionId.equals(s.ownerFactionId());
        if (!sameOwnership) {
            return "ownership changed before payment";
        }
        if (s.state() == CaptureState.EXPIRED || clock.getAsLong() > s.ownedUntil() && period == CAPTURE_REWARD_PERIOD) {
            return "ownership expired";
        }
        if (period > 0 && def.income() != null
                && s.ownerSince() + period * def.income().interval().toMillis() > s.ownedUntil()) {
            return "period ends after ownership expiry";
        }
        Optional<FactionRef> faction = factions.byId(factionId);
        if (faction.isEmpty() || !faction.get().normal()) {
            return "faction no longer exists";
        }
        return null;
    }

    private List<UUID> recipients(String factionId) {
        FactionRef faction = factions.byId(factionId).orElse(null);
        if (faction == null) {
            return List.of();
        }
        if (settings.recipient() == Settings.IncomeRecipient.LEADER) {
            return factions.leaderOf(faction).map(List::of).orElse(List.of());
        }
        return factions.onlineMembers(faction).stream().map(Player::getUniqueId).toList();
    }

    private void finish(String key, PayoutRepository.Status status, String detail, String factionId, double amount, String kind) {
        payouts.complete(key, status, detail).whenComplete((ok, err) -> {
            if (err != null) {
                logger.log(Level.SEVERE, "Payout " + key + " finished as " + status + " but the ledger update failed;"
                        + " it stays PENDING and becomes UNKNOWN on restart", err);
            }
            mainThread.execute(() -> inFlight.remove(key));
        });
        audit.record("SYSTEM", "economy." + kind + "." + status.name().toLowerCase(Locale.ROOT), key,
                "faction=" + factionId + " amount=" + String.format(Locale.ROOT, "%.2f", amount) + " " + detail);
    }
}
