package com.openlibrary.loans;

import java.time.Instant;

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
            boolean open) {
    }

    /** The policy settings the desk can see and understand. */
    public record SettingsSummary(int loanDays, int readerLimit, int maxRenewals) {
    }
}
