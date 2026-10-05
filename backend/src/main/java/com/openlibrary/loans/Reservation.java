package com.openlibrary.loans;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A reader waiting for a book. The queue is the created_at order. */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Reservation() {
    }

    public Reservation(Long bookId, Long userId, Instant createdAt) {
        this.bookId = bookId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public boolean isOpen() {
        return fulfilledAt == null && cancelledAt == null;
    }

    public void fulfil(Instant at) {
        this.fulfilledAt = at;
    }

    public void cancel(Instant at) {
        this.cancelledAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getBookId() {
        return bookId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
