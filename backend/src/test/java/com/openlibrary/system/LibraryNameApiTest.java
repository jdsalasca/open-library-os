package com.openlibrary.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * "Open Library OS" is the project name, not the library's name. Somebody
 * self-hosting this is running <em>their</em> library, and the login screen and
 * the due slip should say so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LibraryNameApiTest extends PostgresTest {

@LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder passwords;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        jdbc.update("update app_config set value = 'Mi Biblioteca' where key = 'library.name'");
    }

    @Test
    void theNameIsReadableBeforeSigningIn() {
        var client = new HttpTestClient(port);

        var name = client.get("/system/library");

        // The login screen has no session yet, so this cannot require one.
        assertThat(name.status()).isEqualTo(200);
        assertThat(name.text("name")).isEqualTo("Mi Biblioteca");
    }

    @Test
    void anAdministratorCanRenameTheLibrary() {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", "admin@demo.test", "password", "Admin12345!")).status()).isEqualTo(200);

        var renamed = client.put("/system/library/name", Map.of("name", "Biblioteca del Barrio"));
        assertThat(renamed.status()).as("rename: %s", renamed.body()).isEqualTo(200);

        assertThat(new HttpTestClient(port).get("/system/library").text("name"))
                .isEqualTo("Biblioteca del Barrio");
    }

    @Test
    void anEmptyNameIsRefused() {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", "admin@demo.test", "password", "Admin12345!")).status()).isEqualTo(200);

        assertThat(client.put("/system/library/name", Map.of("name", "   ")).status()).isEqualTo(400);
        assertThat(client.put("/system/library/name", Map.of("name", "")).status()).isEqualTo(400);
    }

    @Test
    void aNameLongerThanTheColumnIsRefusedRatherThanTruncated() {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", "admin@demo.test", "password", "Admin12345!")).status()).isEqualTo(200);
        String tooLong = "a".repeat(300);

        assertThat(client.put("/system/library/name", Map.of("name", tooLong)).status()).isEqualTo(400);
    }

    @Test
    void onlyAnAdministratorRenamesTheLibrary() {
        var clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login", Map.of("email", "administrativo@demo.test", "password", "Demo12345!")).status()).isEqualTo(200);

        assertThat(clerk.put("/system/library/name", Map.of("name", "No me toca")).status())
                .isEqualTo(403);
    }

    @Test
    void theDeadKeysAreGone() {
        // They were written by V1 and read by nothing: config that lies is worse
        // than config that is absent.
        var keys = jdbc.queryForList("select key from app_config", String.class);

        assertThat(keys).contains("library.name", "loans.days_default");
        assertThat(keys).doesNotContain(
                "inventory.barcode_prefix", "isbn.providers", "library.locale");
    }
}