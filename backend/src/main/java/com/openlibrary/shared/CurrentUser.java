package com.openlibrary.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller to a user id for the audit trail.
 *
 * <p>The SecurityContext only carries the email, and the audit table stores an id.
 * Doing that lookup here keeps it in one place instead of letting every module
 * reach into the user table.
 */
@Component
public class CurrentUser {

    private final JdbcTemplate jdbc;

    public CurrentUser(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : auth.getName();
    }

    /** Null when the caller is anonymous or has been removed meanwhile. */
    public Long id() {
        String email = email();
        if (email == null) {
            return null;
        }
        var ids = jdbc.query(
                "select id from users where lower(email) = lower(?)",
                (rs, i) -> rs.getLong(1), email);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * The authorities the session carries, so a service can tell a reader from
     * the desk without repeating the role maths. Empty when nobody is signed in.
     */
    public java.util.Set<String> authorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return java.util.Set.of();
        }
        return auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
