package com.openlibrary.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One line of "this copy was here, then it went there". */
@Entity
@Table(name = "copy_moves")
public class CopyMove {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "copy_id", nullable = false)
    private Long copyId;

    @Column(name = "from_location_id")
    private Long fromLocationId;

    @Column(name = "to_location_id")
    private Long toLocationId;

    @Column(name = "moved_at", nullable = false)
    private Instant movedAt = Instant.now();

    @Column(name = "user_id")
    private Long userId;

    protected CopyMove() {
    }

    public CopyMove(Long copyId, Long from, Long to, Long userId) {
        this.copyId = copyId;
        this.fromLocationId = from;
        this.toLocationId = to;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public Long getCopyId() {
        return copyId;
    }

    public Long getFromLocationId() {
        return fromLocationId;
    }

    public Long getToLocationId() {
        return toLocationId;
    }

    public Instant getMovedAt() {
        return movedAt;
    }

    public Long getUserId() {
        return userId;
    }
}