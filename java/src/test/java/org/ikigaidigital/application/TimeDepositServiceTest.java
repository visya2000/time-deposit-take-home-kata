package org.ikigaidigital.application;

import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDepositServiceTest {

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
            TimeDepositService service = new TimeDepositService(
                    new InMemoryTimeDepositRepository(withWithdrawals, withoutWithdrawals));

            assertThat(service.getAll()).containsExactly(withoutWithdrawals, withWithdrawals);
        }

        @Test
        void returnsEmptyList_whenRepositoryIsEmpty() {
            TimeDepositService service = new TimeDepositService(new InMemoryTimeDepositRepository());

            assertThat(service.getAll()).isEmpty();
        }
    }

    @Nested
    class GetPage {

        // Non-contiguous ids: catches the service altering afterId (e.g. afterId + 1) before passing it on.
        private final TimeDepositService service = new TimeDepositService(new InMemoryTimeDepositRepository(
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
            TimeDepositService service = new TimeDepositService(new InMemoryTimeDepositRepository(deposit));

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

    private static TimeDepositWithWithdrawals deposit(int id) {
        return new TimeDepositWithWithdrawals(id, "basic", new BigDecimal("1000.00"), 31, List.of());
    }

    private static List<Integer> ids(TimeDepositPage page) {
        return page.items().stream().map(TimeDepositWithWithdrawals::id).toList();
    }
}
