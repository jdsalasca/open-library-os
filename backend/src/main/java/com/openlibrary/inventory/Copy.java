package com.openlibrary.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One physical copy of a book. Several copies share a book, never a code. */
@Entity
@Table(name = "copies")
public class Copy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false, unique = true)
    private String barcode;

    @Column(nullable = false, unique = true)
    private String qr;

    @Column(name = "location_id")
    private Long locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopyStatus status = CopyStatus.DISPONIBLE;

    @Column(name = "acquired_at")
    private LocalDate acquiredAt;

    private BigDecimal price;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Copy() {
    }

    public Copy(Long bookId, String code, String barcode, String qr) {
        this.bookId = bookId;
        this.code = code;
        this.barcode = barcode;
        this.qr = qr;
    }

    /** Statuses a copy can be in. Loans drive DISPONIBLE <-> PRESTADO. */
    public void setStatus(CopyStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void moveTo(Long locationId) {
        this.locationId = locationId;
        this.updatedAt = Instant.now();
    }

    public void setDetails(Long locationId, LocalDate acquiredAt, BigDecimal price, String notes) {
        this.locationId = locationId;
        this.acquiredAt = acquiredAt;
        this.price = price;
        this.notes = notes;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getBookId() {
        return bookId;
    }

    public String getCode() {
        return code;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getQr() {
        return qr;
    }

    public Long getLocationId() {
        return locationId;
    }

    public CopyStatus getStatus() {
        return status;
    }

    public LocalDate getAcquiredAt() {
        return acquiredAt;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}