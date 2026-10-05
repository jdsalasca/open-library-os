package com.openlibrary.inventory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record LocationRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull LocationKind kind,
            Long parentId,
            Integer sortOrder,
            @DecimalMin("-1000") @DecimalMax("1000") BigDecimal x,
            @DecimalMin("-1000") @DecimalMax("1000") BigDecimal y,
            @DecimalMin("-1000") @DecimalMax("1000") BigDecimal z,
            @DecimalMin("0") BigDecimal width,
            @DecimalMin("0") BigDecimal depth,
            @DecimalMin("0") BigDecimal height) {
    }

/**
 * A place in the building, with where it stands.
 *
 * <p>The geometry is what the 3D map draws, so it travels with the node: an
 * editor that has to ask for the coordinates separately is an editor nobody
 * uses. A missing coordinate means "not placed yet", which is not the same as
 * standing at the origin.
 */
public record LocationSummary(
   Long id,
   String code,
   String name,
   LocationKind kind,
   Long parentId,
   java.math.BigDecimal x,
   java.math.BigDecimal y,
   java.math.BigDecimal z,
   java.math.BigDecimal width,
   java.math.BigDecimal depth,
   java.math.BigDecimal height,
   List<LocationSummary> children,
   long copies) {
}

    public record CreateCopiesRequest(
            @NotNull Long bookId,
            /** Bounded: 500 labels in one request is already a lot of plastic. */
            @Min(1) @Max(200) int quantity,
            Long locationId,
            LocalDate acquiredAt,
            @DecimalMin("0") BigDecimal price) {
    }

    public record BulkResult(int requested, List<CopySummary> created) {
    }

    public record MoveRequest(@NotNull Long toLocationId) {
    }

    public record ChangeStatusRequest(@NotNull CopyStatus status) {
    }

    public record CopySummary(
            Long id,
            String code,
            String barcode,
            String qr,
            CopyStatus status,
            Long bookId,
            String bookTitle,
            String bookIsbn,
            Long locationId,
            String locationCode) {
    }

    public record MoveView(Long id, String fromCode, String toCode, String movedAt, String movedBy) {
    }

    /** One catalogue row with its stock, for the inventory screen. */
    public record BookStock(
            Long bookId,
            String title,
            String subtitle,
            List<String> authors,
            String isbn13,
            int copies,
            int available) {
    }
}