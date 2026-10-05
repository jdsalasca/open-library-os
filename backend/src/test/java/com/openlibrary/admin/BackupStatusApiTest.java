package com.openlibrary.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * "My data is mine, and it survives" is the promise this project sells. Right now
 * the only way to check it is to read the backup container's logs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "openlibrary.backup.dir=${java.io.tmpdir}/olo-backup-test")
class BackupStatusApiTest extends PostgresTest {

    private static final String DIR = System.getProperty("java.io.tmpdir") + "/olo-backup-test";

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private Path backups;

    @BeforeEach
    void setUp() throws Exception {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        backups = Path.of(DIR);
        deleteRecursively(backups);
        Files.createDirectories(backups);
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // A file that will not go is reported by the status itself.
                }
            });
        }
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private void dump(String stamp, int sizeBytes) throws Exception {
        Files.write(backups.resolve("openlibrary-" + stamp + ".dump"),
                new byte[sizeBytes]);
    }

    private void marker(long epochSeconds) throws Exception {
        Files.writeString(backups.resolve(".last-ok"), Long.toString(epochSeconds));
    }

    @Test
    void saysSoWhenNoBackupHasEverRun() {
        var status = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD)
                .get("/backup/status");

        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().path("healthy").asBoolean()).isFalse();
        // This project omits null fields, so "never run" arrives as an absent field.
assertThat(status.json().has("lastRun")).as("body: %s", status.body()).isFalse();
        assertThat(status.json().path("message").asText()).contains("todavia");
    }

    @Test
    void reportsAFreshBackupWithItsSizeAndHowManyAreKept() throws Exception {
        dump("20260101T000000Z", 2048);
        dump("20260102T000000Z", 4096);
        marker(Instant.now().getEpochSecond());

        var status = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD)
                .get("/backup/status");

        assertThat(status.json().path("healthy").asBoolean()).isTrue();
        assertThat(status.json().path("dumps").asInt()).isEqualTo(2);
        assertThat(status.json().path("newest").path("name").asText())
                .isEqualTo("openlibrary-20260102T000000Z.dump");
        assertThat(status.json().path("newest").path("bytes").asLong()).isEqualTo(4096);
        assertThat(status.json().path("lastRun").asText()).isNotBlank();
    }

    @Test
    void callsOutABackupThatHasGoneStale() throws Exception {
        dump("20260101T000000Z", 2048);
        // Three days ago, with a daily schedule: something is wrong.
        marker(Instant.now().minusSeconds(3 * 86_400).getEpochSecond());

        var status = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD)
                .get("/backup/status");

        assertThat(status.json().path("healthy").asBoolean()).isFalse();
        assertThat(status.json().path("hoursSinceLast").asLong()).isGreaterThan(48);
    }

    @Test
    void aMarkerWithoutAnyDumpIsNotHealthy() throws Exception {
        marker(Instant.now().getEpochSecond());

        var status = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD)
                .get("/backup/status");

        // The marker can outlive its dump if the volume was pruned by hand.
        assertThat(status.json().path("healthy").asBoolean()).isFalse();
    }

    @Test
    void whoManagesBackupsIsWhoOwnsTheData() {
        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);
        var librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);
        var admin = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD);

        // Readers and desk staff have no business counting other people's backups.
        assertThat(reader.get("/backup/status").status()).isEqualTo(403);
        assertThat(librarian.get("/backup/status").status()).isEqualTo(403);
        // Administrative staff own the data, so they are allowed to look.
        assertThat(clerk.get("/backup/status").status()).isEqualTo(200);
        assertThat(admin.get("/backup/status").status()).isEqualTo(200);
    }
}