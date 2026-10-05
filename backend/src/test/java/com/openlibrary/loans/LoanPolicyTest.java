package com.openlibrary.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.openlibrary.loans.LoanPolicy.CopySnapshot;
import com.openlibrary.loans.LoanPolicy.QueueEntry;
import com.openlibrary.loans.LoanPolicy.Settings;

/**
 * The borrowing rules, with no Spring and no database: a loan either breaks one
 * of these or it is legal. Every rejection carries a machine-readable code so the
 * UI can explain it instead of saying "operación no permitida".
 */
class LoanPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Settings DEFAULTS = new Settings(
            Duration.ofDays(21), 5, 2, 3);

    // ── availability ─────────────────────────────────────────────────────────

    @Test
    void lendsAnAvailableCopy() {
        var request = new LoanPolicy.Request(7L, 42L, 0, 0, List.of(), null);

        var loan = LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK);

        assertThat(loan.dueAt()).isEqualTo(NOW.plus(Duration.ofDays(21)));
        assertThat(loan.status()).isEqualTo(LoanPolicy.Status.ACTIVE);
    }

    @Test
    void refusesACopyThatIsNotAvailable() {
        var request = new LoanPolicy.Request(7L, 42L, 1, 0, List.of(),
                CopySnapshot.unavailable(7L));

        assertThatThrownBy(() -> LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("copy_not_available");
    }

    @Test
    void refusesACopyAlreadyOnLoan() {
        var request = new LoanPolicy.Request(7L, 42L, 1, 0, List.of(),
                CopySnapshot.lent(7L, 99L));

        assertThatThrownBy(() -> LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("copy_not_available");
    }

    // ── reader limits ────────────────────────────────────────────────────────

    @Test
    void refusesToExceedTheReadersLimit() {
        var request = new LoanPolicy.Request(7L, 42L, 5, 0, List.of(), null);

        assertThatThrownBy(() -> LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("reader_limit_reached");
    }

    @Test
    void allowsTheLastSlotOfTheLimit() {
        var request = new LoanPolicy.Request(7L, 42L, 4, 0, List.of(), null);

        assertThat(LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK)).isNotNull();
    }

    @Test
    void refusesADelinquentReader() {
        var request = new LoanPolicy.Request(7L, 42L, 0, 1, List.of(), null);

        assertThatThrownBy(() -> LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("reader_has_overdue_loans");
    }

    // ── reservations ─────────────────────────────────────────────────────────

    @Test
    void skipsOwnReservationWhenLending() {
        var request = new LoanPolicy.Request(7L, 42L, 0, 0,
                List.of(new LoanPolicy.QueueEntry(1L, 42L, NOW)), null);

        var loan = LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK);

        assertThat(loan.status()).isEqualTo(LoanPolicy.Status.ACTIVE);
    }

    @Test
    void refusesWhenSomebodyElseIsWaitingForThatBook() {
        var request = new LoanPolicy.Request(7L, 42L, 0, 0,
                List.of(new LoanPolicy.QueueEntry(1L, 77L, NOW)), null);

        assertThatThrownBy(() -> LoanPolicy.checkBorrow(request, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("book_reserved_by_other_reader");
    }

    // ── renewal ──────────────────────────────────────────────────────────────

    @Test
    void renewsExtendingTheDueDate() {
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(11)), 0, null, LoanPolicy.Status.ACTIVE);

        var renewed = LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK);

        assertThat(renewed.dueAt())
                .isEqualTo(loan.dueAt().plus(DEFAULTS.loanDays()));
        assertThat(renewed.renewals()).isEqualTo(1);
        assertThat(renewed.status()).isEqualTo(LoanPolicy.Status.RENEWED);
    }

    @Test
    void renewingOnTheFirstDayStillBuysYouTheFullPeriod() {
        // Borrowed today: renewing today used to hand back the very same date,
        // so the button gained the reader nothing at all.
        var loan = new LoanPolicy.Loan(5L, NOW, NOW.plus(DEFAULTS.loanDays()),
                0, null, LoanPolicy.Status.ACTIVE);

        var renewed = LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK);

        assertThat(renewed.dueAt())
                .isEqualTo(NOW.plus(DEFAULTS.loanDays()).plus(DEFAULTS.loanDays()));
    }

    @Test
    void renewingLateNeverShortensTheLoan() {
        // Due tomorrow: a period is added to the due date, not counted from
        // today, or renewing late would cost the reader days.
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(1)), 0, null, LoanPolicy.Status.ACTIVE);

        var renewed = LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK);

        assertThat(renewed.dueAt())
                .isEqualTo(NOW.plus(Duration.ofDays(1)).plus(DEFAULTS.loanDays()));
    }

    @Test
    void refusesToRenewBeyondTheCap() {
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(11)), 2, null, LoanPolicy.Status.ACTIVE);

        assertThatThrownBy(() -> LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("renewal_limit_reached");
    }

    @Test
    void refusesToRenewAnOverdueLoan() {
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(40)),
                NOW.minus(Duration.ofDays(19)), 0, null, LoanPolicy.Status.ACTIVE);

        assertThatThrownBy(() -> LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("loan_overdue");
    }

    @Test
    void refusesToRenewWhenSomebodyElseIsWaiting() {
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(11)), 0, null, LoanPolicy.Status.ACTIVE);
        var queue = List.of(new LoanPolicy.QueueEntry(1L, 77L, NOW));

        assertThatThrownBy(() -> LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK, queue))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("book_reserved_by_other_reader");
    }

    @Test
    void refusesToRenewAReturnedLoan() {
        var loan = new LoanPolicy.Loan(5L, NOW.minus(Duration.ofDays(10)),
                NOW.minus(Duration.ofDays(1)), 0, NOW.minus(Duration.ofHours(2)), LoanPolicy.Status.ACTIVE);

        assertThatThrownBy(() -> LoanPolicy.checkRenewal(loan, DEFAULTS, CLOCK))
                .isInstanceOf(LoanPolicy.RuleViolation.class)
                .extracting(e -> ((LoanPolicy.RuleViolation) e).code())
                .isEqualTo("loan_not_active");
    }

    // ── due dates ────────────────────────────────────────────────────────────

    @Test
    void theDueDateFollowsTheConfiguredPeriod() {
        var settings = new Settings(Duration.ofDays(7), 5, 2, 3);
        var request = new LoanPolicy.Request(7L, 42L, 0, 0, List.of(), null);

        assertThat(LoanPolicy.checkBorrow(request, settings, CLOCK).dueAt())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void overdueIsDecidedByCalendarDayNotByTheHour() {
        // Due at 10:30 on the 4th: still fine at 23:59 of the 4th, late from the 5th.
        // Counting whole days is what a library tells its readers, and an hourly
        // grace period turns into endless complaints at the desk.
        var dueAt = Instant.parse("2026-10-04T10:30:00Z");
        var sameDayLate = Instant.parse("2026-10-04T23:59:00Z");
        var nextDay = Instant.parse("2026-10-05T00:01:00Z");

        assertThat(LoanPolicy.isOverdue(dueAt, sameDayLate)).isFalse();
        assertThat(LoanPolicy.isOverdue(dueAt, nextDay)).isTrue();
    }

    @Test
    void reportsTheLocalDateOfTheDueDate() {
        var dueAt = LocalDate.of(2026, 10, 25).atStartOfDay().toInstant(ZoneOffset.UTC);

        assertThat(LoanPolicy.dueDate(dueAt)).isEqualTo(LocalDate.of(2026, 10, 25));
    }
}
