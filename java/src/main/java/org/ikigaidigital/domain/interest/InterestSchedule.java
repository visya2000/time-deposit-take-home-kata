package org.ikigaidigital.domain.interest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Monthly due dates of a deposit. {@link #next} is exactly when the due rule
 * ({@code marker <= today.minusMonths(1)}) makes a deposit due again: the same day one month later,
 * or the 1st of the following month when that day does not exist (Jan 31 -> Mar 1 -> Apr 1).
 * Plain {@code plusMonths} would not match it (Jan 31 + 1 month = Feb 28 -> two postings in March).
 */
public final class InterestSchedule {

    private InterestSchedule() {
    }

    public static LocalDate next(LocalDate dueDate) {
        LocalDate sameDayNextMonth = dueDate.plusMonths(1);
        return sameDayNextMonth.getDayOfMonth() == dueDate.getDayOfMonth()
                ? sameDayNextMonth
                : sameDayNextMonth.plusMonths(1).withDayOfMonth(1);
    }

    /** {@code first}, {@code next(first)}, ... up to and including {@code today}; empty if first is after today. */
    public static List<LocalDate> dueDatesFrom(LocalDate first, LocalDate today) {
        List<LocalDate> dueDates = new ArrayList<>();
        for (LocalDate dueDate = first; !dueDate.isAfter(today); dueDate = next(dueDate)) {
            dueDates.add(dueDate);
        }
        return dueDates;
    }
}
