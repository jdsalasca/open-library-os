package com.openlibrary.isbn;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISBN autofill end to end: the endpoint, the database cache and the fallback when
 * the outside provider has nothing.
 *
 * <p>It logs in over real HTTP instead of disabling security: the autofill endpoint is
 * behind authentication in production, and a test that opened a hole in the filter
 * chain would not be testing the thing that ships.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = IsbnLookupApiTest.Config.class)
class IsbnLookupApiTest extends PostgresTest {

    /** Counts provider calls so we can prove the cache actually prevents repeats. */
    static final class CountingProvider extends OpenLibraryProvider {
        static int calls;

        CountingProvider() {
            super(org.springframework.web.client.RestClient.builder()
                    .baseUrl("http://127.0.0.1:1").build());
        }

        @Override
        public Optional<ExternalBook> lookup(Isbn isbn) {
            calls++;
            return Optional.of(new ExternalBook.Mutable(
                    isbn.normalised(), "Cached Neuromancer", null,
                    List.of("William Gibson"), "Ace", 1984,
                    List.of("Science fiction"), "en", 271,
                    "Summary", null).toRecord());
        }
    }

    @TestConfiguration
    static class Config {
        /** Named differently on purpose: Spring rejects re-registering an existing
         *  bean name, so the test stub must not shadow the production one by name.
         *  @Primary decides which one IsbnLookupService receives. */
        @Bean
        @Primary
        OpenLibraryProvider stubOpenLibraryProvider() {
            return new CountingProvider();
        }
    }

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

        client = new HttpTestClient(port);
        client.post("/auth/login",
                Map.of("email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_PASSWORD));
        // Seeded users start with mustChangePassword; the gate answers 403 until the
        // password is rotated, exactly as it would in the browser.
        client.put("/auth/password", Map.of(
                "currentPassword", DemoUsers.ADMIN_PASSWORD,
                "newPassword", DemoUsers.ADMIN_NEW_PASSWORD));
        client = new HttpTestClient(port);
        client.post("/auth/login", Map.of(
                "email", DemoUsers.ADMIN_EMAIL, "password", DemoUsers.ADMIN_NEW_PASSWORD));

        CountingProvider.calls = 0;
        jdbc.update("DELETE FROM isbn_cache");
    }

    @Test
    void fillsTheBookAndCachesItInTheDatabase() {
        HttpTestClient.Result first = client.get("/isbn/9780306406157");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).contains("Cached Neuromancer");

        Integer cached = jdbc.queryForObject(
                "SELECT count(*) FROM isbn_cache WHERE isbn = '9780306406157'", Integer.class);
        assertThat(cached).isEqualTo(1);

        // Second call must come from Postgres, not from Open Library.
        assertThat(client.get("/isbn/9780306406157").status()).isEqualTo(200);
        assertThat(CountingProvider.calls).as("second call must be served from cache")
                .isEqualTo(1);
    }

    @Test
    void storesTheSourceSoTheUiCanSayWhereEachFieldCameFrom() {
        client.get("/isbn/9780306406157");

        String source = jdbc.queryForObject(
                "SELECT source FROM isbn_cache WHERE isbn = '9780306406157'", String.class);
        assertThat(source).isEqualTo("openlibrary");
    }

    @Test
    void anEmptyLookupIsRememberedSoWeDoNotAskAgain() {
        CountingProvider.calls = 0;
        jdbc.update("INSERT INTO isbn_cache (isbn, source, payload) VALUES (?, ?, ?::jsonb)",
                "9780306406157", "openlibrary", "null");

        HttpTestClient.Result response = client.get("/isbn/9780306406157");

        assertThat(response.status()).isEqualTo(404);
        assertThat(CountingProvider.calls).as("a miss must not be retried on every keystroke")
                .isZero();
    }

    @Test
    void rejectsAnIsbnThatFailsItsChecksumWithoutCallingAnyone() {
        HttpTestClient.Result response = client.get("/isbn/9780306406158");

        assertThat(response.status()).isEqualTo(400);
        assertThat(CountingProvider.calls).isZero();
        Integer cached = jdbc.queryForObject(
                "SELECT count(*) FROM isbn_cache WHERE isbn = '9780306406158'", Integer.class);
        assertThat(cached).isZero();
    }

    @Test
    void refusesTheLookupToSomebodyWithoutASession() {
        HttpTestClient.Result response = new HttpTestClient(port).get("/isbn/9780306406157");

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void anUnparseableIsbnIsRejectedSoTheUiCanOfferTheManualForm() {
        HttpTestClient.Result response = client.get("/isbn/not-an-isbn-at-all");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.text("code")).isEqualTo("invalid_isbn");
    }
}
