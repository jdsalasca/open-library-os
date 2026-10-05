package com.openlibrary.loans;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One copy travelling to one reader. Closed loans keep their history forever. */
@Entity
@Table(name = "loans")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "copy_id", nullable = false)
    private Long copyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "borrowed_at", nullable = false)
    private Instant borrowedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "renewals", nullable = false)
    private int renewals;

    protected Loan() {
    }

    public Loan(Long copyId, Long userId, Instant borrowedAt, Instant dueAt) {
        this.copyId = copyId;
        this.userId = userId;
        this.borrowedAt = borrowedAt;
        this.dueAt = dueAt;
    }

    public boolean isOpen() {
        return returnedAt == null;
    }

    public void renew(Instant newDueAt) {
        this.dueAt = newDueAt;
        this.renewals += 1;
    }

    public void returned(Instant at) {
        this.returnedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getCopyId() {
        return copyId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getBorrowedAt() {
        return borrowedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public int getRenewals() {
        return renewals;
    }
}
