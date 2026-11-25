/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.progression;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.economy.IncomeService;
import dev.aponder.drugrun.territories.territory.CaptureEvent;
import dev.aponder.drugrun.territories.territory.CaptureListener;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryState;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns confirmed capture outcomes into progression, money rewards and history rows. Only
 * completed captures and successful defences reward anything; starts and failures are history only.
 */
public final class CaptureRewardListener implements CaptureListener {

    private final ProgressionService progression;
    private final IncomeService income;
    private final TerritoryRepository repository;
    private final LongSupplier clock;
    private final Logger logger;

    public CaptureRewardListener(ProgressionService progression, IncomeService income, TerritoryRepository repository,
                                 LongSupplier clock, Logger logger) {
        this.progression = progression;
        this.income = income;
        this.repository = repository;
        this.clock = clock;
        this.logger = logger;
    }

    @Override
    public void onCaptureEvent(TerritoryDefinition territory, CaptureEvent event, TerritoryState state) {
        Settings.Progression cfg = progression.settings();
        switch (event) {
            case CaptureEvent.Completed c -> {
                progression.add(c.factionId(), ProgressDelta.capture(cfg.captureXp() + territory.rewards().xp(),
                        cfg.captureReputation() + territory.rewards().reputation()));
                income.payCaptureReward(territory, state);
                history(territory, c.factionId(), "COMPLETED");
            }
            case CaptureEvent.Defended d -> {
                progression.add(d.ownerFactionId(), ProgressDelta.defense(cfg.defenseXp(), cfg.defenseReputation()));
                history(territory, d.ownerFactionId(), "DEFENDED");
            }
            case CaptureEvent.Started s -> history(territory, s.factionId(), s.challenge() ? "CHALLENGE_STARTED" : "STARTED");
            case CaptureEvent.Failed f -> history(territory, f.factionId(), "FAILED_" + f.reason().name());
            case CaptureEvent.Expired e -> history(territory, e.formerOwnerId(), "EXPIRED");
            case CaptureEvent.Revoked r -> history(territory, r.formerOwnerId(), "REVOKED");
            case CaptureEvent.Contested c -> {
                // Frequent and transient; not stored.
            }
            case CaptureEvent.Resumed r -> {
                // Frequent and transient; not stored.
            }
            case CaptureEvent.Available a -> {
                // Implied by the preceding event.
            }
        }
    }

    private void history(TerritoryDefinition territory, String factionId, String what) {
        repository.history(territory.id(), factionId, what.toUpperCase(Locale.ROOT), clock.getAsLong()).exceptionally(t -> {
            logger.log(Level.WARNING, "Could not record capture history for " + territory.id(), t);
            return null;
        });
    }
}
