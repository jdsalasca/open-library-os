package com.openlibrary.support;

import com.openlibrary.auth.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Fixed accounts used by the integration tests, so they can log in over real HTTP. */
public final class DemoUsers {

    public static final String ADMIN_EMAIL = "admin@demo.test";
    public static final String ADMIN_PASSWORD = "Admin12345!";
    public static final String ADMIN_NEW_PASSWORD = "NuevaClave2026";

    public static final String READER_EMAIL = "lector@demo.test";
    public static final String READER_PASSWORD = "Demo12345!";

    public static final String LIBRARIAN_EMAIL = "bibliotecario@demo.test";
    public static final String LIBRARIAN_PASSWORD = "Demo12345!";

    public static final String CLERK_EMAIL = "administrativo@demo.test";
    public static final String CLERK_PASSWORD = "Demo12345!";

    /** Throwaway account for role changes, so tests never demote a real role. */
    public static final String VICTIM_EMAIL = "victima@demo.test";
    public static final String VICTIM_PASSWORD = "Demo12345!";

    private DemoUsers() {
    }

    /** Inserts the four roles if absent. The admin keeps the change-password flag. */
    public static void seed(DataSource dataSource, PasswordEncoder passwords) {
        insert(dataSource, passwords, ADMIN_EMAIL, ADMIN_PASSWORD, Role.ADMINISTRADOR, true);
        insert(dataSource, passwords, READER_EMAIL, READER_PASSWORD, Role.LECTOR, false);
        insert(dataSource, passwords, LIBRARIAN_EMAIL, LIBRARIAN_PASSWORD, Role.BIBLIOTECARIO, false);
        insert(dataSource, passwords, CLERK_EMAIL, CLERK_PASSWORD, Role.ADMINISTRATIVO, false);
        insert(dataSource, passwords, VICTIM_EMAIL, VICTIM_PASSWORD, Role.LECTOR, false);
    }

    /**
     * Puts every fixture back to its starting state: passwords, the change-password
     * flag and the role. A test that exercises role changes must not leak into the
     * next one, which is exactly how this suite first failed.
     */
    public static void resetPasswords(JdbcTemplate jdbc, PasswordEncoder passwords) {
        reset(jdbc, passwords, ADMIN_EMAIL, ADMIN_PASSWORD, true, Role.ADMINISTRADOR);
        reset(jdbc, passwords, READER_EMAIL, READER_PASSWORD, false, Role.LECTOR);
        reset(jdbc, passwords, LIBRARIAN_EMAIL, LIBRARIAN_PASSWORD, false, Role.BIBLIOTECARIO);
        reset(jdbc, passwords, CLERK_EMAIL, CLERK_PASSWORD, false, Role.ADMINISTRATIVO);
        reset(jdbc, passwords, VICTIM_EMAIL, VICTIM_PASSWORD, false, Role.LECTOR);
    }

    private static void reset(JdbcTemplate jdbc, PasswordEncoder passwords, String email, String raw,
                              boolean mustChange, Role role) {
        jdbc.update(
                "update users set password_hash = ?, must_change_password = ?, active = true, role = ?"
                        + " where email = ?",
                passwords.encode(raw), mustChange, role.name(), email);
    }

    public static String emailFor(Role role) {
        return switch (role) {
            case LECTOR -> READER_EMAIL;
            case BIBLIOTECARIO -> LIBRARIAN_EMAIL;
            case ADMINISTRATIVO -> CLERK_EMAIL;
            case ADMINISTRADOR -> ADMIN_EMAIL;
        };
    }

    public static String passwordFor(String email) {
        return ADMIN_EMAIL.equals(email) ? ADMIN_PASSWORD : READER_PASSWORD;
    }

    private static void insert(DataSource dataSource, PasswordEncoder passwords,
                               String email, String raw, Role role, boolean mustChange) {
        String sql = """
                insert into users (email, password_hash, full_name, role, must_change_password)
                values (?, ?, ?, ?, ?)
                on conflict (lower(email)) do nothing
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, passwords.encode(raw));
            ps.setString(3, label(role) + " de prueba");
            ps.setString(4, role.name());
            ps.setBoolean(5, mustChange);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("no se pudo sembrar " + email, e);
        }
    }

    private static String label(Role role) {
        return switch (role) {
            case LECTOR -> "Lector";
            case BIBLIOTECARIO -> "Bibliotecario";
            case ADMINISTRATIVO -> "Administrativo";
            case ADMINISTRADOR -> "Administrador";
        };
    }
}