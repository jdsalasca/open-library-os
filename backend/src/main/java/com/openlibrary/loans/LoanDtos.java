package com.openlibrary.loans;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class LoanDtos {

    private LoanDtos() {
    }

    public record BorrowRequest(@NotNull @Positive Long copyId,
                                @NotNull @Positive Long readerId) {
    }

    public record ReserveRequest(@NotNull @Positive Long bookId) {
    }

    public record LoanSummary(
            Long id,
            Long copyId,
            String copyCode,
            String barcode,
            Long bookId,
            String bookTitle,
            Long readerId,
            String readerEmail,
            Instant borrowedAt,
            Instant dueAt,
            Instant returnedAt,
            int renewals,
            boolean overdue) {
    }

    public record ReservationSummary(
Long id,
   Long bookId,
   String bookTitle,
   String coverHint,
   Instant createdAt,
   boolean open,
   // Who is waiting: the desk has to hand the book to somebody, and five rows
   // saying the same title tell it nothing.
   Long readerId,
   String readerName,
   String readerEmail,
   Integer place) {
}

    /** The policy settings the desk can see and understand. */
    public record SettingsSummary(int loanDays, int readerLimit, int maxRenewals) {
    }

    /** One reservation as its owner sees it: place in the queue and availability. */
    public record MyReservation(
            Long id,
            Long bookId,
            String bookTitle,
            Instant createdAt,
            int place,
            int queueLength,
            boolean availableNow) {
    }

    /** A reader as the desk needs them: who they are and what they already hold. */
    public record DeskReader(
            Long id,
            String email,
            String fullName,
            int activeLoans,
            int overdue) {
    }

    /** Someone who is late, as the desk needs them: who to call and by how much. */
    public record UrgentLoan(
            String readerName,
            String readerEmail,
            String bookTitle,
            int daysLate) {
    }

    /** The first screen's numbers, for staff. */
    public record Dashboard(
            int out,
            int overdue,
            int dueToday,
            int available,
            List<UrgentLoan> urgent) {
    }

    /** The lending policy as the administrator sets it. */
    public record SettingsRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(365)
            int loanDays,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(50)
            int readerLimit,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(10)
            int maxRenewals) {
    }

/** Everything a card holder sees about themselves. */
    public record MyLibrary(
            List<LoanSummary> loans,
            List<LoanSummary> history,
            List<MyReservation> reservations) {
    }
}
