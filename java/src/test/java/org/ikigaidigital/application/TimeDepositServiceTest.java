package org.ikigaidigital.application;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;
import org.ikigaidigital.domain.interest.BasicInterestStrategy;
import org.ikigaidigital.domain.interest.InterestStrategies;
import org.ikigaidigital.domain.interest.InterestStrategy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeDepositServiceTest {

    private static final int CHUNK_SIZE = 2;

    @Nested
    class GetAll {

        @Test
        void returnsEveryDepositWithItsWithdrawals() {
            TimeDepositWithWithdrawals withWithdrawals = new TimeDepositWithWithdrawals(
                    2, "premium", new BigDecimal("2500.50"), 60, List.of(
                            new Withdrawal(10, new BigDecimal("100.00"), LocalDate.of(2026, 1, 15)),
                            new Withdrawal(11, new BigDecimal("50.25"), LocalDate.of(2026, 2, 1))));
            TimeDepositWithWithdrawals withoutWithdrawals = new TimeDepositWithWithdrawals(
                    1, "basic", new BigDecimal("1000.00"), 31, List.of());
            TimeDepositService service = serviceFor(
                    new InMemoryTimeDepositRepository(withWithdrawals, withoutWithdrawals));

            assertThat(service.getAll()).containsExactly(withoutWithdrawals, withWithdrawals);
        }

        @Test
        void returnsEmptyList_whenRepositoryIsEmpty() {
            TimeDepositService service = serviceFor(new InMemoryTimeDepositRepository());

            assertThat(service.getAll()).isEmpty();
        }
    }

    @Nested
    class GetPage {

        // Non-contiguous ids: catches the service altering afterId (e.g. afterId + 1) before passing it on.
        private final TimeDepositService service = serviceFor(new InMemoryTimeDepositRepository(
                deposit(3), deposit(7), deposit(8), deposit(12), deposit(20)));

        @Test
        void returnsAllRemainingDeposits_withoutNextPage_whenExactlyLimitRemain() {
            TimeDepositPage page = service.getPage(7, 3);

            assertThat(ids(page)).containsExactly(8, 12, 20);
            assertThat(page.hasNext()).isFalse();
        }

        @Test
        void returnsFirstLimitDeposits_withNextPage_whenLimitPlusOneRemain() {
            TimeDepositPage page = service.getPage(3, 3);

            assertThat(ids(page)).containsExactly(7, 8, 12);
            assertThat(page.hasNext()).isTrue();
        }

        @Test
        void returnsLastPartialPage_withoutNextPage_whenFewerThanLimitRemain() {
            TimeDepositPage page = service.getPage(8, 5);

            assertThat(ids(page)).containsExactly(12, 20);
            assertThat(page.hasNext()).isFalse();
        }

        @Test
        void returnsEmptyPage_withoutNextPage_whenAfterIdIsBeyondHighestId() {
            TimeDepositPage page = service.getPage(20, 2);

            assertThat(page.items()).isEmpty();
            assertThat(page.hasNext()).isFalse();
        }

        @Test
        void startsWithFirstDepositAfterAfterId_whenAfterIdIsNotAnExistingId() {
            TimeDepositPage page = service.getPage(5, 2);

            assertThat(ids(page)).containsExactly(7, 8);
            assertThat(page.hasNext()).isTrue();
        }

        @Test
        void returnsDepositsWithTheirWithdrawals() {
            TimeDepositWithWithdrawals deposit = new TimeDepositWithWithdrawals(
                    1, "student", new BigDecimal("750.00"), 100, List.of(
                            new Withdrawal(5, new BigDecimal("20.00"), LocalDate.of(2026, 3, 10))));
            TimeDepositService service = serviceFor(new InMemoryTimeDepositRepository(deposit));

            assertThat(service.getPage(0, 1).items()).containsExactly(deposit);
        }

        @Test
        void walkingAllPages_yieldsEveryDepositExactlyOnce_inIdOrder() {
            List<Integer> walkedIds = new ArrayList<>();
            int afterId = 0;
            TimeDepositPage page;
            do {
                page = service.getPage(afterId, 2);
                assertThat(page.items()).isNotEmpty();
                walkedIds.addAll(ids(page));
                afterId = page.items().get(page.items().size() - 1).id();
            } while (page.hasNext());

            assertThat(walkedIds).containsExactly(3, 7, 8, 12, 20);
        }
    }

    /**
     * Basic plan, 1200.00, eligible: one month of interest is exactly 1.00 (1200.00 -> 1201.00); a
     * second posting would give 1202.00. Chunk size 2, so most scenarios span several chunks.
     * The preemptive timeout keeps a non-terminating run (missing id cursor) from hanging the build.
     */
    @Nested
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class UpdateAllBalances {

        private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
        private static final BigDecimal INITIAL = new BigDecimal("1200.00");
        private static final BigDecimal AFTER_ONE_POSTING = new BigDecimal("1201.00");

        private final InMemoryTimeDepositRepository repository = new InMemoryTimeDepositRepository();

        @Test
        void appliesInterestToAllDueDeposits_acrossMultipleChunks() {
            List<Integer> ids = List.of(3, 7, 8, 12, 20);
            ids.forEach(id -> repository.add(id, "basic", "1200.00", 31));

            runOn(TODAY);

            ids.forEach(id -> assertPostedOn(id, TODAY));
        }

        @Test
        void terminatesAndReachesLaterDeposits_whenManyDueDepositsStayUnchanged() {
            // Deposits in their grace period stay due after evaluation (marker not advanced); without
            // the id cursor every chunk query would return them again and the run would never end.
            IntStream.rangeClosed(1, 7).forEach(id -> repository.add(id, "basic", "1200.00", 10));
            repository.add(8, "basic", "1200.00", 31);

            runOn(TODAY); // guarded by the class-level timeout

            IntStream.rangeClosed(1, 7).forEach(this::assertUntouched);
            assertPostedOn(8, TODAY);
        }

        @Test
        void completesWithoutChanges_whenRepositoryIsEmpty() {
            runOn(TODAY);

            assertThat(repository.findAllWithWithdrawals()).isEmpty();
        }

        @Test
        void appliesInterestToSingleDeposit() {
            repository.add(1, "basic", "1200.00", 31);

            runOn(TODAY);

            assertPostedOn(1, TODAY);
        }

        @Test
        void secondRunOnSameDay_changesNothing() {
            IntStream.rangeClosed(1, 3).forEach(id -> repository.add(id, "basic", "1200.00", 31));
            runOn(TODAY);

            runOn(TODAY);

            IntStream.rangeClosed(1, 3).forEach(id -> assertPostedOn(id, TODAY));
        }

        @ParameterizedTest(name = "last interest {0} -> not due on {1}")
        @CsvSource({
                "2026-01-31, 2026-02-28",
                "2028-01-31, 2028-02-29",
                "2026-10-15, 2026-11-14"
        })
        void depositIsNotDue_beforeOneCalendarMonthHasPassed(LocalDate lastInterestAppliedOn, LocalDate runDate) {
            repository.add(1, "basic", "1200.00", 400, lastInterestAppliedOn);

            runOn(runDate);

            assertThat(repository.balanceOf(1)).isEqualTo(INITIAL);
            assertThat(repository.lastInterestAppliedOn(1)).isEqualTo(lastInterestAppliedOn);
        }

        @ParameterizedTest(name = "last interest {0} -> due on {1}")
        @CsvSource({
                "2026-01-31, 2026-03-01",
                "2028-01-31, 2028-03-01",
                "2026-10-15, 2026-11-15"
        })
        void depositIsDueAgain_oneCalendarMonthAfterLastInterest(LocalDate lastInterestAppliedOn, LocalDate runDate) {
            repository.add(1, "basic", "1200.00", 400, lastInterestAppliedOn);

            runOn(runDate);

            assertPostedOn(1, runDate);
        }

        @ParameterizedTest(name = "{0}, {1}, {2} days")
        @CsvSource({
                "basic,   1200.00, 30",   // grace period
                "premium, 1200.00, 45",   // premium waiting period
                "student, 1200.00, 366",  // student plan ended
                "gold,    1200.00, 400",  // unknown plan
                "basic,   0.50,    400"   // interest rounds to 0.00
        })
        void advancesMarkerOnlyForDepositsWhoseBalanceChanged(String planType, String balance, int days) {
            repository.add(1, "basic", "1200.00", 31);
            repository.add(2, planType, balance, days); // same chunk as deposit 1

            runOn(TODAY);

            assertPostedOn(1, TODAY);
            assertThat(repository.balanceOf(2)).isEqualTo(new BigDecimal(balance));
            assertThat(repository.lastInterestAppliedOn(2)).isNull();
        }

        @Test
        void depositInGracePeriod_getsInterestOnFirstRunAfterBecomingEligible() {
            repository.add(1, "basic", "1200.00", 30);
            runOn(LocalDate.of(2026, 11, 10));
            assertUntouched(1);

            repository.setDays(1, 31);
            runOn(LocalDate.of(2026, 11, 11));

            assertPostedOn(1, LocalDate.of(2026, 11, 11));
        }

        @Test
        void failureInMiddleChunk_keepsEarlierChunks_andLeavesFailedAndLaterChunksUnchanged() {
            TimeDepositService service = serviceWithFailureInSecondChunk();

            assertThatThrownBy(service::updateAllBalances)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("simulated failure");

            assertPostedOn(1, TODAY);
            assertPostedOn(2, TODAY);
            IntStream.rangeClosed(3, 6).forEach(this::assertUntouched); // 3 was calculated before the failure
        }

        @Test
        void rerunAfterFailure_completesRemainingDeposits_withoutDoublePosting() {
            TimeDepositService service = serviceWithFailureInSecondChunk();
            assertThatThrownBy(service::updateAllBalances).hasMessage("simulated failure");

            service.updateAllBalances();

            IntStream.rangeClosed(1, 6).forEach(id -> assertPostedOn(id, TODAY));
        }

        @Test
        void recordsBusinessDateInClockZone() {
            // 2026-11-30 in UTC, but already 2026-12-01 08:30 in Tokyo.
            Clock tokyo = Clock.fixed(Instant.parse("2026-11-30T23:30:00Z"), ZoneId.of("Asia/Tokyo"));
            repository.add(1, "basic", "1200.00", 31);

            newService(repository, new TimeDepositCalculator(), tokyo).updateAllBalances();

            assertPostedOn(1, LocalDate.of(2026, 12, 1));
        }

        @Test
        void runSpanningMidnight_recordsDateTheRunStartedForAllChunks() {
            IntStream.rangeClosed(1, 3).forEach(id -> repository.add(id, "basic", "1200.00", 31));

            newService(repository, new TimeDepositCalculator(), nextDayAfterFirstRead(TODAY)).updateAllBalances();

            IntStream.rangeClosed(1, 3).forEach(id -> assertPostedOn(id, TODAY));
        }

        private void runOn(LocalDate date) {
            newService(repository, new TimeDepositCalculator(), startOfDayUtc(date)).updateAllBalances();
        }

        /** Chunks {1, 2}, {3, 4}, {5, 6}; deposit 4 fails on its first calculation. */
        private TimeDepositService serviceWithFailureInSecondChunk() {
            IntStream.rangeClosed(1, 6)
                    .filter(id -> id != 4)
                    .forEach(id -> repository.add(id, "basic", "1200.00", 31));
            repository.add(4, FailingOnceInterestStrategy.PLAN_TYPE, "1200.00", 31);
            List<InterestStrategy> strategies = new ArrayList<>(InterestStrategies.defaults());
            strategies.add(new FailingOnceInterestStrategy());
            return newService(repository, new TimeDepositCalculator(strategies), startOfDayUtc(TODAY));
        }

        private void assertPostedOn(int id, LocalDate date) {
            assertThat(repository.balanceOf(id)).as("balance of %d", id).isEqualTo(AFTER_ONE_POSTING);
            assertThat(repository.lastInterestAppliedOn(id)).as("marker of %d", id).isEqualTo(date);
        }

        private void assertUntouched(int id) {
            assertThat(repository.balanceOf(id)).as("balance of %d", id).isEqualTo(INITIAL);
            assertThat(repository.lastInterestAppliedOn(id)).as("marker of %d", id).isNull();
        }
    }

    /** Test plan that fails on its first calculation (a failure mid-run) and then earns basic interest. */
    private static class FailingOnceInterestStrategy implements InterestStrategy {

        static final String PLAN_TYPE = "failing-once";

        private final InterestStrategy basic = new BasicInterestStrategy();
        private boolean failed;

        @Override
        public String planType() {
            return PLAN_TYPE;
        }

        @Override
        public double monthlyInterest(TimeDeposit deposit) {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("simulated failure");
            }
            return basic.monthlyInterest(deposit);
        }
    }

    private static Clock startOfDayUtc(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    /** UTC clock at the start of {@code date} on its first read and one day later on every further read. */
    private static Clock nextDayAfterFirstRead(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Clock() {
            private boolean read;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new AssertionError("not expected to be called");
            }

            @Override
            public Instant instant() {
                Instant instant = read ? start.plus(Duration.ofDays(1)) : start;
                read = true;
                return instant;
            }
        };
    }

    private static TimeDepositService serviceFor(InMemoryTimeDepositRepository repository) {
        return newService(repository, new TimeDepositCalculator(), Clock.systemUTC());
    }

    private static TimeDepositService newService(InMemoryTimeDepositRepository repository,
                                                 TimeDepositCalculator calculator, Clock clock) {
        return new TimeDepositService(
                repository, new InMemoryTransactionRunner(repository), calculator, clock, CHUNK_SIZE);
    }

    private static TimeDepositWithWithdrawals deposit(int id) {
        return new TimeDepositWithWithdrawals(id, "basic", new BigDecimal("1000.00"), 31, List.of());
    }

    private static List<Integer> ids(TimeDepositPage page) {
        return page.items().stream().map(TimeDepositWithWithdrawals::id).toList();
    }
}
