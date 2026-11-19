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
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeDepositServiceTest {

    private static final int CHUNK_SIZE = 2;

    /** Chunk size is configuration: below 1 every run would be a silent no-op (PLAN.md §8 step 10). */
    @Nested
    class Construction {

        private final InMemoryTimeDepositRepository repository = new InMemoryTimeDepositRepository();

        @ParameterizedTest(name = "chunk size {0}")
        @ValueSource(ints = {0, -1})
        void rejectsChunkSizeBelowOne(int chunkSize) {
            assertThatThrownBy(() -> serviceWithChunkSize(chunkSize))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void chunkSizeOne_isAccepted_andAppliesInterestToEveryDueDeposit() {
            repository.add(1, "basic", "1200.00", 31);
            repository.add(2, "basic", "1200.00", 31);

            serviceWithChunkSize(1).updateAllBalances();

            assertThat(List.of(repository.balanceOf(1), repository.balanceOf(2)))
                    .containsExactly(new BigDecimal("1201.00"), new BigDecimal("1201.00"));
        }

        private TimeDepositService serviceWithChunkSize(int chunkSize) {
            return new TimeDepositService(repository, new InMemoryTransactionRunner(repository),
                    new TimeDepositCalculator(), Clock.systemUTC(), chunkSize);
        }
    }

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

        // Student row (step 16): a never-posted student deposit older than 365 days is owed the months
        // it was eligible (back-fill), so "plan ended" needs a marker: last posted on 2026-10-19 at
        // age 349, its next due date 2026-11-19 is at age 380 -> no interest, marker kept.
        @ParameterizedTest(name = "{0}, {1}, {2} days, last interest {3}")
        @CsvSource(nullValues = "never", value = {
                "basic,   1200.00, 30,  never",       // grace period
                "premium, 1200.00, 45,  never",       // premium waiting period
                "student, 1200.00, 380, 2026-10-19",  // student plan ended
                "gold,    1200.00, 400, never",       // unknown plan
                "basic,   0.50,    400, never"        // interest rounds to 0.00
        })
        void advancesMarkerOnlyForDepositsWhoseBalanceChanged(String planType, String balance, int days,
                                                             LocalDate lastInterestAppliedOn) {
            repository.add(1, "basic", "1200.00", 31);
            repository.add(2, planType, balance, days, lastInterestAppliedOn); // same chunk as deposit 1

            runOn(TODAY);

            assertPostedOn(1, TODAY);
            assertThat(repository.balanceOf(2)).isEqualTo(new BigDecimal(balance));
            assertThat(repository.lastInterestAppliedOn(2)).isEqualTo(lastInterestAppliedOn);
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

    /**
     * Back-fill of missed months (PLAN.md §3 "Backfilling missed months", step 16): a late run posts
     * every owed month as daily runs would have, each on the balance after the previous posting
     * re-rounded to cents, judging eligibility by the deposit's age on each due date
     * ({@code days - daysBetween(dueDate, today)}); the marker becomes the last due date that
     * earned interest (as daily runs mark only postings that changed the balance), not the run date.
     * Expected values are derived by hand from the plan rules (interest = balance * rate / 12
     * rounded HALF_UP to cents). Never-eligible deposits (grace period, unknown
     * plan, interest rounding to 0.00) are covered by {@code advancesMarkerOnlyForDepositsWhoseBalanceChanged}.
     */
    @Nested
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    class Backfill {

        private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

        private final InMemoryTimeDepositRepository repository = new InMemoryTimeDepositRepository();

        @Test
        void markerSet_threeMissedMonths_postsThreeCompoundedMonths_andMarksTheLastDueDate() {
            // Run on 11-25 (not a due date); due 09-19, 10-19, 11-19. Basic 1 % / 12:
            // 120000.00 + 100.00 = 120100.00; + 100.08 (100.0833..) = 120200.08; + 100.17 (100.1667..)
            // = 120300.25. Without compounding: 120300.00.
            repository.add(1, "basic", "120000.00", 400, LocalDate.of(2026, 8, 19));

            runOn(LocalDate.of(2026, 11, 25));

            assertState(1, "120300.25", LocalDate.of(2026, 11, 19));
        }

        @Test
        void reRoundsTheBalanceToCentsBetweenPostings_likeOnTimeRunsThatStoreIt() {
            // Due 10-19, 11-19. Premium 5 % / 12: 3.59 -> 0.01495.. -> 0.01 -> 3.60 (stored in cents);
            // 3.60 -> 0.015 -> 0.02 -> 3.62. Without re-rounding the double 3.59 + 0.01 is
            // 3.5999999999999996, its interest just below 0.015 rounds to 0.01 -> 3.61.
            repository.add(1, "premium", "3.59", 400, LocalDate.of(2026, 9, 19));

            runOn(TODAY);

            assertState(1, "3.62", TODAY);
        }

        @Test
        void monthEndMarker_afterAnOutage_postsOnTheFirstOfTheMonth_andKeepsThatCycle() {
            // next(01-31) = 03-01 (no Feb 31), then 04-01; 05-01 is after the run date.
            // Basic: 1200.00 -> 1201.00 -> 1202.00 (1.0008.. -> 1.00).
            repository.add(1, "basic", "1200.00", 400, LocalDate.of(2026, 1, 31));

            runOn(LocalDate.of(2026, 4, 15));

            assertState(1, "1202.00", LocalDate.of(2026, 4, 1));
        }

        @Test
        void depositSkippedForOneDay_isPostedForItsOriginalDueDate_andKeepsItsCycle() {
            // Due on 11-19 but processed on 11-20 (e.g. its row was locked): marker 11-19, not 11-20.
            repository.add(1, "basic", "1200.00", 400, LocalDate.of(2026, 10, 19));

            runOn(LocalDate.of(2026, 11, 20));

            assertState(1, "1201.00", LocalDate.of(2026, 11, 19));
        }

        // Student, 380 days on 11-19: age on a due date = 380 - days until 11-19. The marker becomes
        // the last due date that earned interest; a later due date that earned nothing does not move it.
        @ParameterizedTest(name = "last interest {0} -> {1}, marker {2}")
        @CsvSource({
                // due 10-04 (age 334) and 11-04 (age 365, last day with interest):
                // 1000.00 + 2.50 = 1002.50; + 2.51 (2.50625) = 1005.01
                "2026-09-04, 1005.01, 2026-11-04",
                // due 10-05 (age 335): 1002.50; due 11-05 (age 366): plan ended, nothing
                "2026-09-05, 1002.50, 2026-10-05"
        })
        void studentPlan_backfillsOnlyTheDueDatesOnWhichItWasStillEligible(
                LocalDate lastInterestAppliedOn, String expectedBalance, LocalDate expectedMarker) {
            repository.add(1, "student", "1000.00", 380, lastInterestAppliedOn);

            runOn(TODAY);

            assertState(1, expectedBalance, expectedMarker);
        }

        // Premium with a marker set before it became eligible (e.g. migrated); due 10-19 and 11-19.
        @ParameterizedTest(name = "{0} days -> {1}")
        @CsvSource({
                "76, 1205.00", // 10-19 at age 45: nothing; 11-19 at age 76: 5.00
                "77, 1210.02"  // 10-19 at age 46: 5.00; 11-19 at age 77: 5.02 (1205.00 * 0.05 / 12 = 5.0208..)
        })
        void premiumPlan_earnsNothingOnDueDatesUpToAge45_andInterestOnLaterOnes(int days, String expectedBalance) {
            repository.add(1, "premium", "1200.00", days, LocalDate.of(2026, 9, 19));

            runOn(TODAY);

            assertState(1, expectedBalance, TODAY);
        }

        @Test
        void premiumPlan_whoseOnlyMissedDueDateIsInsideTheWaitingPeriod_isNotPosted_andKeepsItsMarker() {
            // 50 days today; the only due date 10-20 is at age 20 (11-20 is after today).
            repository.add(1, "premium", "1200.00", 50, LocalDate.of(2026, 9, 20));

            runOn(TODAY);

            assertState(1, "1200.00", LocalDate.of(2026, 9, 20));
        }

        // Never posted: opened (today - days); the first due date is the first day the plan earns
        // interest (basic/student: age 31, premium: age 46), then monthly up to today.
        @ParameterizedTest(name = "{0}, {1} days -> {2}, marker {3}")
        @CsvSource({
                "basic,   31,  1201.00, 2026-11-19", // opened 10-19; age 31 is today: one posting, as before
                "basic,   61,  1201.00, 2026-10-20", // opened 09-19; age 31 on 10-20; next 11-20 is after today
                "basic,   62,  1202.00, 2026-11-19", // opened 09-18; age 31 on 10-19; then 11-19
                "basic,   92,  1203.00, 2026-11-19", // opened 08-19; age 31 on 09-19; then 10-19, 11-19
                "premium, 46,  1205.00, 2026-11-19", // age 46 is today
                "premium, 76,  1205.00, 2026-10-20", // opened 09-04; age 46 on 10-20; next 11-20 is after today
                "premium, 77,  1210.02, 2026-11-19", // opened 09-03; age 46 on 10-19: 5.00; 11-19: 5.02
                // opened 2025-11-18; age 31 on 2025-12-19; 11 postings 2025-12-19 .. 2026-10-19 (age 335):
                // 1200.00 -> 1203.00 -> 1206.01 -> 1209.03 -> 1212.05 -> 1215.08 -> 1218.12 -> 1221.17
                // -> 1224.22 -> 1227.28 -> 1230.35 -> 1233.43; 2026-11-19 (age 366) earns nothing, so the
                // marker stays on the last posting
                "student, 366, 1233.43, 2026-10-19"
        })
        void neverPostedDeposit_isOwedEveryMonthSinceItFirstBecameEligible(
                String planType, int days, String expectedBalance, LocalDate expectedMarker) {
            repository.add(1, planType, "1200.00", days);

            runOn(TODAY);

            assertState(1, expectedBalance, expectedMarker);
        }

        @Test
        void postsEveryMissedMonth_withoutACap() {
            // Basic 10.xx earns exactly 0.01 a month (0.0083.. to 0.0086.. -> 0.01), so the balance
            // counts the postings: due 2024-06-19 .. 2026-11-19 = 7 + 12 + 11 = 30 months -> 10.30.
            repository.add(1, "basic", "10.00", 1000, LocalDate.of(2024, 5, 19));

            runOn(TODAY);

            assertState(1, "10.30", TODAY);
        }

        // The design goal: one late run gives what daily runs would have given. The external system
        // ages the deposit by one day before each daily run.
        @ParameterizedTest(name = "{0}, {1}, opened {2}, run on {3}")
        @CsvSource({
                "basic,   120000.00, 2025-12-31, 2026-06-15", // first due 01-31, then 03-01, 04-01, ...
                "premium, 3.59,      2026-01-15, 2026-06-15", // cents re-rounding between postings
                "student, 1000.00,   2025-06-01, 2026-05-20", // age 353 at the end: still in its window
                "student, 1000.00,   2025-06-01, 2026-07-15", // crosses day 365: later months earn 0
                "student, 1200.00,   2025-01-15, 2026-01-15"  // age 365 on the run date: 12 postings
        })
        void oneLateRun_givesTheSameBalanceAndMarker_asDailyRuns(String planType, String balance,
                                                                  LocalDate openedOn, LocalDate runDate) {
            InMemoryTimeDepositRepository daily = new InMemoryTimeDepositRepository();
            daily.add(1, planType, balance, 0);
            for (LocalDate date = openedOn; !date.isAfter(runDate); date = date.plusDays(1)) {
                daily.setDays(1, (int) ChronoUnit.DAYS.between(openedOn, date));
                runOn(daily, date);
            }
            repository.add(1, planType, balance, (int) ChronoUnit.DAYS.between(openedOn, runDate));

            runOn(repository, runDate);

            assertThat(daily.balanceOf(1)).as("precondition: daily runs posted interest")
                    .isGreaterThan(new BigDecimal(balance));
            assertThat(repository.balanceOf(1)).isEqualTo(daily.balanceOf(1));
            assertThat(repository.lastInterestAppliedOn(1)).isEqualTo(daily.lastInterestAppliedOn(1));
        }

        private void runOn(LocalDate date) {
            runOn(repository, date);
        }

        private void runOn(InMemoryTimeDepositRepository target, LocalDate date) {
            newService(target, new TimeDepositCalculator(), startOfDayUtc(date)).updateAllBalances();
        }

        private void assertState(int id, String expectedBalance, LocalDate expectedMarker) {
            assertThat(repository.balanceOf(id)).as("balance of %d", id).isEqualTo(new BigDecimal(expectedBalance));
            assertThat(repository.lastInterestAppliedOn(id)).as("marker of %d", id).isEqualTo(expectedMarker);
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
