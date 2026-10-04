package com.openlibrary.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator so a fresh deployment is usable immediately.
 *
 * Runs only when there are no users at all, so it can never resurrect or
 * overwrite an existing account after a restart or a restore.
 */
@Component
public class InitialAdminSeeder implements ApplicationRunner {

    static final String DEFAULT_EMAIL = "admin@local";

    private final UserRepository users;
    private final PasswordEncoder passwords;

    public InitialAdminSeeder(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }

        String email = env("OLO_ADMIN_EMAIL", DEFAULT_EMAIL);
        String password = env("OLO_ADMIN_PASSWORD", "ChangeMe!2026");

        users.save(new User(email, passwords.encode(password), "Administrador", Role.ADMINISTRADOR, true));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}