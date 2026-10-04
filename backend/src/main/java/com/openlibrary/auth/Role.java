package com.openlibrary.auth;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single place where a role becomes a set of authorities. Security rules in
 * {@code SecurityConfig} only ever mention these strings, so the permission model
 * stays readable and testable without booting Spring.
 */
public enum Role {

    LECTOR,
    BIBLIOTECARIO,
    ADMINISTRATIVO,
    ADMINISTRADOR;

    public static final String CATALOG_READ = "catalog:read";
    public static final String CATALOG_WRITE = "catalog:write";
    public static final String INVENTORY_WRITE = "inventory:write";
    public static final String LOANS_OPERATE = "loans:operate";
    public static final String LOANS_SELF = "loans:self";
    public static final String USERS_READ = "users:read";
    public static final String USERS_WRITE = "users:write";
    public static final String USERS_ROLES = "users:roles";
    public static final String SETTINGS_MANAGE = "settings:manage";
    public static final String BACKUP_MANAGE = "backup:manage";

    private static final Set<String> READER = Set.of(CATALOG_READ, LOANS_SELF);

    public Set<String> authorities() {
        return switch (this) {
            case LECTOR -> READER;
            // Librarians run the desk: stock and loans, but not the catalogue rules.
            case BIBLIOTECARIO -> concat(READER, INVENTORY_WRITE, LOANS_OPERATE);
            // Administrative staff own the data and the people.
            case ADMINISTRATIVO -> concat(
                    READER, CATALOG_WRITE, INVENTORY_WRITE, LOANS_OPERATE,
                    USERS_READ, USERS_WRITE, BACKUP_MANAGE);
            // Listed explicitly: deriving it from values() would recurse into itself.
            case ADMINISTRADOR -> Set.of(
                    CATALOG_READ, CATALOG_WRITE, INVENTORY_WRITE, LOANS_OPERATE, LOANS_SELF,
                    USERS_READ, USERS_WRITE, USERS_ROLES, SETTINGS_MANAGE, BACKUP_MANAGE);
        };
    }

    private static Set<String> concat(Set<String> base, String... extra) {
        var all = new LinkedHashSet<>(base);
        all.addAll(Arrays.asList(extra));
        return Set.copyOf(all);
    }
}