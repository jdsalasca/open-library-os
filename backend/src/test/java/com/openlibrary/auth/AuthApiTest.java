package com.openlibrary.auth;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("delete from users where email = 'nuevo@demo.test'");
        client = new HttpTestClient(port);
    }

    /** Logs in and clears the must-change-password gate, leaving a usable session. */
    private HttpTestClient loginAsAdmin() {
        client.post("/auth/login",
                Map.of("email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_PASSWORD));
        client.put("/auth/password", Map.of(
                "currentPassword", DemoUsers.ADMIN_PASSWORD,
                "newPassword", DemoUsers.ADMIN_NEW_PASSWORD));
        client.post("/auth/logout", null);
        client.post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_NEW_PASSWORD));
        return client;
    }

    @Test
    void issuesAWorkingCsrfTokenToAnonymousClients() {
        var result = client.get("/auth/csrf");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text("token")).isNotBlank();
    }

    @Test
    void loginReturnsTheProfileAndStoresTheSessionInTheDatabase() {
        var login = client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("email")).isEqualTo(DemoUsers.READER_EMAIL);
        assertThat(login.text("role")).isEqualTo("LECTOR");

        Integer rows = jdbc.queryForObject("select count(*) from SPRING_SESSION", Integer.class);
        assertThat(rows).as("sessions must live in Postgres, not in RAM").isPositive();
    }

    @Test
    void rejectsAWrongPassword() {
        var login = client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", "wrong-password"));

        assertThat(login.status()).isEqualTo(401);
    }

    @Test
    void rejectsAnUnknownEmailWithTheSameAnswer() {
        var login = client.post("/auth/login", Map.of(
                "email", "nadie@demo.test", "password", DemoUsers.READER_PASSWORD));

        assertThat(login.status()).isEqualTo(401);
        assertThat(login.body()).doesNotContain("nadie@demo.test");
    }

    @Test
    void rejectsALoginWithoutCsrfToken() {
        var login = client.postWithoutCsrf("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        assertThat(login.status()).isEqualTo(403);
    }

    @Test
    void meIsReachableOnceAuthenticated() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        var me = client.get("/auth/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("role")).isEqualTo("LECTOR");
    }

    @Test
    void meIsForbiddenForAnonymousClients() {
        assertThat(client.get("/auth/me").status()).isEqualTo(401);
    }

    @Test
    void logoutDestroysTheServerSideSession() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));
        assertThat(client.get("/auth/me").status()).isEqualTo(200);

        assertThat(client.post("/auth/logout", null).status()).isEqualTo(204);

        assertThat(client.get("/auth/me").status()).isEqualTo(401);
    }

    @Test
    void everyLoginIsWrittenToTheAuditTrail() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        Integer count = jdbc.queryForObject(
                "select count(*) from audit_log where action = 'auth.login'", Integer.class);

        assertThat(count).isPositive();
    }

    @Test
    void theSeededAdminMustChangeItsPasswordBeforeDoingAnythingElse() {
        var login = client.post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_PASSWORD));

        assertThat(login.text("mustChangePassword")).isEqualTo("true");
        assertThat(client.get("/auth/me").status()).isEqualTo(200);
        assertThat(client.get("/users").status())
                .as("blocked until the password is changed")
                .isEqualTo(403);
    }

    @Test
    void changingThePasswordClearsTheFlagAndRejectsTheOldOne() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_PASSWORD));

        var changed = client.put("/auth/password", Map.of(
                "currentPassword", DemoUsers.ADMIN_PASSWORD,
                "newPassword", DemoUsers.ADMIN_NEW_PASSWORD));
        assertThat(changed.status()).isEqualTo(204);

        client.post("/auth/logout", null);
        assertThat(new HttpTestClient(port)
                .post("/auth/login", Map.of("email", DemoUsers.ADMIN_EMAIL,
                        "password", DemoUsers.ADMIN_PASSWORD))
                .status()).isEqualTo(401);

        var withNew = new HttpTestClient(port).post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_NEW_PASSWORD));
        assertThat(withNew.status()).isEqualTo(200);
        assertThat(withNew.text("mustChangePassword")).isEqualTo("false");
    }

    @Test
    void passwordsShorterThanTenCharactersAreRefused() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        var result = client.put("/auth/password", Map.of(
                "currentPassword", DemoUsers.READER_PASSWORD, "newPassword", "corta123"));

        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    void theCurrentPasswordMustBeCorrect() {
        client.post("/auth/login", Map.of(
                "email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD));

        var result = client.put("/auth/password", Map.of(
                "currentPassword", "no-es-la-buena", "newPassword", "OtraClave2026"));

        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    void anAdminCanCreateAndDeactivateAReader() {
        loginAsAdmin();

        var created = client.post("/users", Map.of(
                "email", "nuevo@demo.test",
                "fullName", "Lector Nuevo",
                "role", "LECTOR",
                "password", "Temporal123!"));
        assertThat(created.status()).isEqualTo(201);

        var disabled = client.put("/users/" + created.text("id"),
                Map.of("active", false));
        assertThat(disabled.status()).isEqualTo(200);

        var login = new HttpTestClient(port).post("/auth/login", Map.of(
                "email", "nuevo@demo.test", "password", "Temporal123!"));
        assertThat(login.status()).as("deactivated accounts cannot log in").isEqualTo(401);
    }

    @Test
    void duplicateEmailsAreRejectedWithAFieldError() {
        loginAsAdmin();

        var duplicate = client.post("/users", Map.of(
                "email", DemoUsers.ADMIN_EMAIL,
                "fullName", "Otro",
                "role", "LECTOR",
                "password", "Temporal123!"));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body()).contains("email");
    }
}