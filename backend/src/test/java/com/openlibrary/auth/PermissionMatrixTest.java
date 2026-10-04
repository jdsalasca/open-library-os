package com.openlibrary.auth;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every role against every guarded endpoint, over real HTTP with a real session.
 *
 * <p>A denied role must always get 401/403. An allowed role gets either the real
 * success code or 404 while the feature is still in a later round, so this matrix
 * can live from round one and stop the day a new rule is wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PermissionMatrixTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    @BeforeEach
    void resetAccounts() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        // This matrix is about authorisation only. The forced password change is a
        // separate rule with its own tests in AuthApiTest; leaving it on would make
        // every admin check answer 403 for the wrong reason.
        jdbc.update("update users set must_change_password = false");
    }

    private long victimId() {
        return jdbc.queryForObject("select id from users where email = ?",
                Long.class, DemoUsers.VICTIM_EMAIL);
    }

    private HttpTestClient sessionFor(Role role) {
        String email = DemoUsers.emailFor(role);
        String raw = DemoUsers.passwordFor(email);

        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", raw)).status())
                .as("login as %s", role).isEqualTo(200);
        return client;
    }

    /** Expected status per role: LECTOR, BIBLIOTECARIO, ADMINISTRATIVO, ADMINISTRADOR. */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", "/users", "listar usuarios",
                        403, 403, 200, 200),
                Arguments.of("POST", "/users", "crear usuario",
                        403, 403, 201, 201),
                Arguments.of("PUT", "/users/1", "editar usuario",
                        403, 403, 200, 200),
                Arguments.of("GET", "/auth/me", "perfil propio",
                        200, 200, 200, 200),
                // Readers browse the catalogue; that is the whole point of a library.
                Arguments.of("GET", "/catalog/books", "leer catalogo",
                        200, 200, 200, 200),
                Arguments.of("POST", "/catalog/books", "escribir catalogo",
                        403, 403, 201, 201),
                // Stock is staff-only: readers see availability through the catalogue.
                Arguments.of("GET", "/inventory/copies", "leer inventario",
                        403, 200, 200, 200),
                Arguments.of("POST", "/inventory/copies", "escribir inventario",
                        403, 201, 201, 201),
                Arguments.of("GET", "/loans", "ver todos los prestamos",
                        403, 200, 200, 200),
                Arguments.of("POST", "/loans", "operar prestamos",
                        403, 201, 201, 201),
                Arguments.of("GET", "/reservations", "reservas propias",
                        200, 200, 200, 200),
                Arguments.of("GET", "/settings", "ajustes",
                        403, 403, 403, 200),
                Arguments.of("GET", "/backup", "respaldos",
                        403, 403, 200, 200));
    }

    @ParameterizedTest(name = "{0} {1} -> L:{5} BI:{6} AD:{7} ADM:{8}")
    @MethodSource("endpoints")
    void onlyTheRightRolesReachEachEndpoint(String method, String path, String what,
                                            int lector, int bibliotecario, int administrativo, int administrador) {
        check(Role.LECTOR, method, path, lector);
        check(Role.BIBLIOTECARIO, method, path, bibliotecario);
        check(Role.ADMINISTRATIVO, method, path, administrativo);
        check(Role.ADMINISTRADOR, method, path, administrador);
    }

    private void check(Role role, String method, String path, int expected) {
        var client = sessionFor(role);

        var result = switch (method) {
            case "GET" -> client.get(path);
            case "POST" -> client.post(path, Map.of());
            case "PUT" -> client.put(path, Map.of());
            case "PATCH" -> client.patch(path, Map.of("role", "LECTOR"));
            default -> throw new IllegalArgumentException(method);
        };

        if (expected == 403) {
            assertThat(result.status())
                    .as("%s %s as %s must be refused -> %s", method, path, role, result.body())
                    .isEqualTo(403);
            return;
        }

        // Allowed: the real success code, 404/400 while the feature is still in a
        // later round. What must never happen is being bounced by security.
        assertThat(result.status())
                .as("%s %s as %s -> %s", method, path, role, result.body())
                .isNotIn(401, 403)
                .isIn(expected, 400, 404);
    }

    @Test
    void anonymousClientsAreRejectedEverywhere() {
        var client = new HttpTestClient(port);

        assertThat(client.get("/users").status()).isEqualTo(401);
        assertThat(client.get("/auth/me").status()).isEqualTo(401);
        assertThat(client.get("/catalog/books").status()).isEqualTo(401);
        assertThat(client.get("/inventory/copies").status()).isEqualTo(401);
    }

    /**
     * Role changes get their own test: they need a real target id, and they must be
     * aimed at a throwaway account. Using a role account here would demote it and
     * silently change every later assertion.
     */
    @Test
    void onlyTheAdministratorCanChangeRoles() {
        long victim = victimId();

        assertThat(sessionFor(Role.ADMINISTRATIVO)
                .patch("/users/" + victim + "/role", Map.of("role", "BIBLIOTECARIO")).status())
                .as("administrative staff manage people but not roles")
                .isEqualTo(403);

        assertThat(sessionFor(Role.ADMINISTRADOR)
                .patch("/users/" + victim + "/role", Map.of("role", "BIBLIOTECARIO")).status())
                .isEqualTo(200);

        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, victim))
                .isEqualTo("BIBLIOTECARIO");
    }

    @Test
    void eachRoleSeesItsOwnAuthorities() {
        for (Role role : Role.values()) {
            var me = sessionFor(role).get("/auth/me");
            assertThat(me.status()).as("me as %s", role).isEqualTo(200);
            assertThat(me.text("role")).as("role of %s", role).isEqualTo(role.name());

            var reported = new java.util.HashSet<String>();
            me.json().path("authorities").forEach(node -> reported.add(node.asText()));

            assertThat(reported)
                    .as("authorities of %s", role)
                    .containsAll(role.authorities());
        }
    }
}