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

/**
 * A place in the building: room, corridor, shelf or depot.
 *
 * <p>x/y/z and the sizes are metres with the origin at the entrance. They are
 * nullable because a library should be able to register its shelves before anyone
 * measures the room, and round 6 draws the same numbers in 3D.
 */
@Entity
@Table(name = "locations")
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationKind kind;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    private BigDecimal x;
    private BigDecimal y;
    private BigDecimal z;
    private BigDecimal width;
    private BigDecimal depth;
    private BigDecimal height;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Location() {
    }

    public Location(String code, String name, LocationKind kind, Long parentId) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.parentId = parentId;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public LocationKind getKind() {
        return kind;
    }

    public Long getParentId() {
        return parentId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public BigDecimal getX() {
        return x;
    }

    public BigDecimal getY() {
        return y;
    }

    public BigDecimal getZ() {
        return z;
    }

    public BigDecimal getWidth() {
        return width;
    }

    public BigDecimal getDepth() {
        return depth;
    }

    public BigDecimal getHeight() {
        return height;
    }
}