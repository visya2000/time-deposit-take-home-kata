package org.ikigaidigital.domain.interest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monthly due dates (PLAN.md §3 "Backfilling missed months"): {@code next} must be exactly the first
 * day on which the due rule {@code marker <= today.minusMonths(1)} holds again.
 */
class InterestScheduleTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-01-15, 2026-02-15", // day of month kept
            "2026-12-15, 2027-01-15", // across the year end
            "2027-02-28, 2027-03-28", // short month as the start: still the same day
            "2026-01-31, 2026-03-01", // Feb 31 does not exist -> 1st of the month after (not Feb 28)
            "2026-03-31, 2026-05-01", // Apr 31 does not exist -> May 1 (not Apr 30)
            "2026-08-31, 2026-10-01", // Sep 31 does not exist
            "2028-01-29, 2028-02-29", // leap year: Feb 29 exists
            "2027-01-29, 2027-03-01", // non-leap year: Feb 29 does not exist
            "2028-01-30, 2028-03-01"  // leap year, but Feb 30 does not exist
    })
    void next_isTheSameDayNextMonth_orTheFirstOfTheMonthAfter_whenThatDayDoesNotExist(
            LocalDate dueDate, LocalDate expectedNext) {
        assertThat(InterestSchedule.next(dueDate)).isEqualTo(expectedNext);
    }

    @Test
    void dueDatesFrom_containsOnlyFirst_whenFirstIsToday() {
        LocalDate today = LocalDate.of(2026, 11, 19);

        assertThat(InterestSchedule.dueDatesFrom(today, today)).containsExactly(today);
    }

    @Test
    void dueDatesFrom_isEmpty_whenFirstIsAfterToday() {
        assertThat(InterestSchedule.dueDatesFrom(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19)))
                .isEmpty();
    }

    @Test
    void dueDatesFrom_containsEveryMonthlyDueDate_upToAndIncludingToday() {
        assertThat(InterestSchedule.dueDatesFrom(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 11, 19)))
                .containsExactly(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 19));
    }

    @Test
    void dueDatesFrom_excludesTheNextDueDate_whenItIsOneDayAfterToday() {
        assertThat(InterestSchedule.dueDatesFrom(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 11, 18)))
                .containsExactly(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 19));
    }

    // Each date follows from the previous one via next, so a month-end start drifts to the 1st once
    // and stays there (Jan 31 -> Mar 1 -> Apr 1), never two dates in one month.
    @Test
    void dueDatesFrom_chainsNext_fromAMonthEndStart() {
        assertThat(InterestSchedule.dueDatesFrom(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 15)))
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
    }

    // The core correctness argument as a property, over leap and non-leap years and every month end:
    // the back-fill's next due date and the SQL due filter must agree on every (marker, today).
    @Test
    void next_isOnOrBeforeToday_exactlyWhenTheDueRuleHolds() {
        for (LocalDate marker = LocalDate.of(2027, 1, 1); marker.isBefore(LocalDate.of(2029, 1, 1));
             marker = marker.plusDays(1)) {
            for (int offset = 0; offset <= 62; offset++) {
                LocalDate today = marker.plusDays(offset);
                assertThat(!InterestSchedule.next(marker).isAfter(today))
                        .as("marker %s, today %s", marker, today)
                        .isEqualTo(!marker.isAfter(today.minusMonths(1)));
            }
        }
    }
}
