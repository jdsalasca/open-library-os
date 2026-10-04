package com.openlibrary.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {
    }

    public User(String email, String passwordHash, String fullName, Role role, boolean mustChangePassword) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
        this.updatedAt = at;
    }

    public void rename(String fullName) {
        this.fullName = fullName;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public void changeRole(Role role) {
        this.role = role;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}