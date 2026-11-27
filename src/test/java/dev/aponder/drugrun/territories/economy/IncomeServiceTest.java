/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.aponder.drugrun.territories.audit.AuditLog;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.progression.ProgressionRepository;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Downtown income: intervals, caps, idempotency and restart safety (spec 5.4, tests 63 and 65). */
class IncomeServiceTest {

    @TempDir
    Path dir;
    private HikariDataStore db;
    private final FakeFactions factions = new FakeFactions();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final UUID leader = UUID.randomUUID();
    private final FakeEconomy economy = new FakeEconomy();
    private TerritoryService territories;
    private ProgressionService progression;
    private PayoutRepository payouts;
    private AuditLog audit;

    static final class FakeEconomy implements EconomyGateway {
        final List<Object[]> deposits = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile boolean refuse;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean deposit(UUID player, double amount) {
            if (refuse) {
                return false;
            }
            deposits.add(new Object[] {player, amount});
            return true;
        }

        @Override
        public String format(double amount) {
            return String.valueOf(amount);
        }

        double total() {
            return deposits.stream().mapToDouble(d -> (Double) d[1]).sum();
        }
    }

    @BeforeEach
    void setUp() {
        db = Fixtures.sqlite(dir);
        factions.add("1", "Alpha");
        factions.leaders.put("1", leader);
        Settings settings = Fixtures.settings();
        progression = new ProgressionService(new ProgressionRepository(db), settings.progression(), 2, Fixtures.LOG);
        progression.load(Map.of(), List.of());
        TerritoryDefinition downtown = Fixtures.withIncome(Fixtures.territory("downtown", 0, 0, Map.of()), 250, Duration.ofMinutes(60), 5);
        territories = new TerritoryService(settings, new TerritoryRegistry(List.of(downtown)), new TerritoryRepository(db), factions,
                progression, (def, now) -> Map.of(), Runnable::run, clock::get, () -> LocalTime.NOON, Fixtures.LOG);
        territories.initialize(Map.of());
        payouts = new PayoutRepository(db);
        audit = new AuditLog(db, Fixtures.LOG);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private IncomeService income(Settings.IncomeRecipient recipient, boolean payMissed) {
        IncomeService s = new IncomeService(territories, factions, progression, payouts, audit, economy,
                new Settings.Income(payMissed, recipient), Runnable::run, clock::get, Fixtures.LOG);
        s.initialize(payouts.claimedPeriods().join());
        return s;
    }

    private long payoutsWith(PayoutRepository.Status status) {
        return payouts.recent(status, 200).join().size();
    }

    @Test
    @DisplayName("Pays once per completed interval, never twice for the same period")
    void paysEachPeriodOnce() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        income.tick();
        assertEquals(0, economy.deposits.size(), "nothing is due before the first interval ends");
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        income.tick();
        income.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 1);
        income.tick();
        assertEquals(1, economy.deposits.size());
        assertEquals(250.0, economy.total(), 1e-9);
        assertEquals(leader, economy.deposits.getFirst()[0]);
        Fixtures.eventually(() -> progression.get("1").incomeEarned() == 250.0);
    }

    @Test
    @DisplayName("Payout cap per ownership period")
    void cap() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        clock.addAndGet(Duration.ofHours(10).toMillis());
        income.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 5);
        assertEquals(1250.0, economy.total(), 1e-9);
    }

    @Test
    @DisplayName("65: a restart never repeats a payout, and offline periods are skipped by default")
    void restartSafety() {
        territories.forceCapture("downtown", "1");
        IncomeService first = income(Settings.IncomeRecipient.LEADER, false);
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        first.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 1);
        // "Restart" after the server was down for two more periods.
        clock.addAndGet(Duration.ofMinutes(120).toMillis());
        IncomeService second = income(Settings.IncomeRecipient.LEADER, false);
        second.tick();
        clock.addAndGet(Duration.ofMinutes(5).toMillis());
        second.tick();
        assertEquals(1, economy.deposits.size(), "no duplicate and no back-pay for downtime");
        clock.addAndGet(Duration.ofMinutes(60).toMillis());
        second.tick();
        Fixtures.eventually(() -> economy.deposits.size() == 2);
    }

    @Test
    @DisplayName("Restart during payout processing: the PENDING row becomes UNKNOWN and is never paid")
    void crashDuringPayout() {
        territories.forceCapture("downtown", "1");
        String ownership = territories.state("downtown").orElseThrow().ownershipId().toString();
        payouts.claim("downtown:" + ownership + ":1", "downtown", ownership, 1, "1", 250).join();
        assertEquals(1, payouts.markPendingUnknown().join());
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        income(Settings.IncomeRecipient.LEADER, true).tick();
        assertEquals(0, economy.deposits.size());
        assertEquals(1, payoutsWith(PayoutRepository.Status.UNKNOWN));
    }

    @Test
    @DisplayName("No payouts to disbanded factions or without an economy; failures are recorded")
    void invalidRecipients() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        economy.available = false;
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        income.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.FAILED) == 1);
        economy.available = true;
        factions.leaders.remove("1");
        clock.addAndGet(Duration.ofMinutes(60).toMillis());
        income.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.FAILED) == 2);
        assertEquals(0, economy.deposits.size());
    }

    @Test
    @DisplayName("No payments after ownership expires")
    void noPaymentAfterExpiry() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        clock.addAndGet(Duration.ofHours(25).toMillis());
        territories.tick();
        income.tick();
        assertEquals(0, payoutsWith(null), "nothing was claimed");
        assertEquals(0, economy.deposits.size(), "the territory expired before anything was claimed");
    }

    @Test
    @DisplayName("ONLINE_MEMBERS splits the payout between online members")
    void split() {
        Player p1 = mock(Player.class);
        Player p2 = mock(Player.class);
        when(p1.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p2.getUniqueId()).thenReturn(UUID.randomUUID());
        factions.online.put("1", List.of(p1, p2));
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.ONLINE_MEMBERS, false);
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        income.tick();
        Fixtures.eventually(() -> economy.deposits.size() == 2);
        assertEquals(250.0, economy.total(), 1e-9);
        assertTrue(economy.deposits.stream().allMatch(d -> (Double) d[1] == 125.0));
    }

    @Test
    @DisplayName("Periods that finished before ownership ended are paid even when the income check had not run yet")
    void finalPeriodsSettledOnExpiry() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        territories.addOwnershipEndListener(income::ownershipEnded);
        clock.addAndGet(Duration.ofMinutes(4 * 60 + 30).toMillis());
        income.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 4);
        // Period 5 ends at 5h, but no income check runs before the ownership expires.
        clock.addAndGet(Duration.ofHours(20).toMillis());
        territories.tick();
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 5);
        income.tick();
        assertEquals(1250.0, economy.total(), 1e-9);
        assertEquals(0, payoutsWith(PayoutRepository.Status.FAILED));
    }

    @Test
    @DisplayName("A period still running when ownership ends is not paid")
    void unfinishedPeriodNotPaid() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.LEADER, false);
        territories.addOwnershipEndListener(income::ownershipEnded);
        clock.addAndGet(Duration.ofMinutes(90).toMillis());
        territories.reset("downtown");
        Fixtures.eventually(() -> payoutsWith(PayoutRepository.Status.PAID) == 1);
        income.tick();
        assertEquals(250.0, economy.total(), 1e-9);
    }

    @Test
    @DisplayName("ONLINE_MEMBERS pays the leader when no member is online")
    void onlineMembersFallBackToLeader() {
        territories.forceCapture("downtown", "1");
        IncomeService income = income(Settings.IncomeRecipient.ONLINE_MEMBERS, false);
        clock.addAndGet(Duration.ofMinutes(61).toMillis());
        income.tick();
        Fixtures.eventually(() -> economy.deposits.size() == 1);
        assertEquals(leader, economy.deposits.getFirst()[0]);
    }
}
