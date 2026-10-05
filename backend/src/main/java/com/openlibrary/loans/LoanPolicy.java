package com.openlibrary.loans;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Every borrowing rule in one place, with no Spring and no database, because the
 * rules are the part of a library that has to be right for years and the part
 * that is cheapest to prove correct in isolation.
 *
 * <p>The service loads the facts, the policy decides. Anything the policy rejects
 * carries a stable code so the UI can explain the refusal instead of showing a
 * generic error.
 */
public final class LoanPolicy {

    private LoanPolicy() {
    }

    /** What the caller already has going: enough to decide, nothing more. */
    public record Request(long bookId, long readerId, int activeLoans, int overdueLoans,
                          List<QueueEntry> queue, CopySnapshot copy) {
    }

    /** The copy as far as the policy cares: free, or not, and why. */
    public record CopySnapshot(long id, boolean available, Long activeLoanId) {

        public static CopySnapshot free(long id) {
            return new CopySnapshot(id, true, null);
        }

        public static CopySnapshot unavailable(long id) {
            return new CopySnapshot(id, false, null);
        }

        public static CopySnapshot lent(long id, long loanId) {
            return new CopySnapshot(id, false, loanId);
        }
    }

    /** Somebody waiting for the book: this reader, or somebody else. */
    public record QueueEntry(long reservationId, long readerId, Instant createdAt) {
    }

    public enum Status {
        ACTIVE, RENEWED
    }

    public record Loan(long copyId, Instant borrowedAt, Instant dueAt, int renewals,
                       Instant returnedAt, Status status) {

        Loan withDue(Instant newDueAt, int newRenewals, Status newStatus) {
            return new Loan(copyId, borrowedAt, newDueAt, newRenewals, returnedAt, newStatus);
        }
    }

    /** A refusal, with the code the API and the UI speak. */
    public static class RuleViolation extends RuntimeException {
        private final String code;

        RuleViolation(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param loanDays      how long a loan lasts before it is due
     * @param readerLimit   how many books a reader may hold at once
     * @param maxRenewals   how many times one loan may be extended
     * @param loanPeriodDays kept for readability of the admin settings screen
     */
    public record Settings(Duration loanDays, int readerLimit, int maxRenewals,
                           int loanPeriodDays) {

        public static Settings defaults() {
            return new Settings(Duration.ofDays(21), 5, 2, 3);
        }
    }

    public static Loan checkBorrow(Request request, Settings settings, Clock clock) {
        if (request.copy() != null && !request.copy().available()) {
            throw new RuleViolation("copy_not_available",
                    "Ese ejemplar no esta disponible.");
        }
        if (request.overdueLoans() > 0) {
            throw new RuleViolation("reader_has_overdue_loans",
                    "Devuelve primero lo que tienes vencido.");
        }
        if (request.activeLoans() >= settings.readerLimit()) {
            throw new RuleViolation("reader_limit_reached",
                    "Ya tienes el maximo de prestamos (" + settings.readerLimit() + ").");
        }
        if (waitingFor(request.queue(), request.readerId())) {
            throw new RuleViolation("book_reserved_by_other_reader",
                    "Ese libro esta reservado por otro lector.");
        }
        Instant now = clock.instant();
        return new Loan(request.copy() == null ? 0 : request.copy().id(),
                now, now.plus(settings.loanDays()), 0, null, Status.ACTIVE);
    }

    public static Loan checkRenewal(Loan loan, Settings settings, Clock clock) {
        return checkRenewal(loan, settings, clock, List.of());
    }

    public static Loan checkRenewal(Loan loan, Settings settings, Clock clock,
                                    List<QueueEntry> queue) {
        if (loan.returnedAt() != null) {
            throw new RuleViolation("loan_not_active", "Ese prestamo ya esta cerrado.");
        }
        Instant now = clock.instant();
        if (isOverdue(loan.dueAt(), now)) {
            throw new RuleViolation("loan_overdue",
                    "No se renueva un prestamo vencido: devuelvelo primero.");
        }
        if (loan.renewals() >= settings.maxRenewals()) {
            throw new RuleViolation("renewal_limit_reached",
                    "Ese prestamo ya se renovo " + loan.renewals() + " veces.");
        }
        // The reader holding the book is never in the queue for it, so any entry
        // here belongs to somebody else.
        if (!queue.isEmpty()) {
            throw new RuleViolation("book_reserved_by_other_reader",
                    "Hay alguien esperando este libro: no se puede renovar.");
        }
        return loan.withDue(now.plus(settings.loanDays()), loan.renewals() + 1, Status.RENEWED);
    }

    /** A loan is late only once the whole due day has passed. */
    public static boolean isOverdue(Instant dueAt, Instant now) {
        return dueDate(dueAt).isBefore(dueDate(now));
    }

    public static LocalDate dueDate(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean waitingFor(List<QueueEntry> queue, long readerId) {
        return queue.stream().anyMatch(entry -> entry.readerId() != readerId);
    }
}
