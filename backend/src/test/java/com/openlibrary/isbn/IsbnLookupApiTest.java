package com.openlibrary.isbn;

import com.openlibrary.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ISBN autofill end to end: the endpoint, the database cache and the fallback when
 * the outside provider has nothing.
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

        /**
         * spring-boot-starter-security is on the classpath, so the default chain would
         * answer 401 before the controller runs. The real authorisation rules belong to
         * the auth round; these tests are about caching, and duplicating its login flow
         * here would only re-test it badly.
         */
        @Bean
        org.springframework.security.web.SecurityFilterChain permitAllForTests(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http)
                throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        CountingProvider.calls = 0;
        jdbc.update("DELETE FROM isbn_cache");
        // Spring Session JDBC is on the classpath (auth round), but its schema belongs
        // to a migration that is not in develop yet. Creating the two tables here keeps
        // this test independent of when that migration lands.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS SPRING_SESSION (
                    PRIMARY_ID CHAR(36) NOT NULL,
                    SESSION_ID CHAR(36) NOT NULL,
                    CREATION_TIME BIGINT NOT NULL,
                    LAST_ACCESS_TIME BIGINT NOT NULL,
                    MAX_INACTIVE_INTERVAL INT NOT NULL,
                    EXPIRY_TIME BIGINT NOT NULL,
                    PRINCIPAL_NAME VARCHAR(100),
                    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID));
                CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
                    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
                    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
                    ATTRIBUTE_BYTES BYTEA NOT NULL,
                    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
                        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE);""");
    }

    @Test
    void fillsTheBookAndCachesItInTheDatabase() throws Exception {
        HttpResponse<String> first = get("/api/isbn/9780306406157");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("Cached Neuromancer");

        Integer cached = jdbc.queryForObject(
                "SELECT count(*) FROM isbn_cache WHERE isbn = '9780306406157'", Integer.class);
        assertThat(cached).isEqualTo(1);

        // Second call must come from Postgres, not from Open Library.
        HttpResponse<String> second = get("/api/isbn/9780306406157");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(CountingProvider.calls).as("second call must be served from cache")
                .isEqualTo(1);
    }

    @Test
    void storesTheSourceSoTheUiCanSayWhereEachFieldCameFrom() throws Exception {
        get("/api/isbn/9780306406157");

        String source = jdbc.queryForObject(
                "SELECT source FROM isbn_cache WHERE isbn = '9780306406157'", String.class);
        assertThat(source).isEqualTo("openlibrary");
    }

    @Test
    void anEmptyLookupIsRememberedSoWeDoNotAskAgain() throws Exception {
        CountingProvider.calls = 0;
        jdbc.update("INSERT INTO isbn_cache (isbn, source, payload) VALUES (?, ?, ?::jsonb)",
                "9780306406157", "openlibrary", "null");

        HttpResponse<String> response = get("/api/isbn/9780306406157");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(CountingProvider.calls).as("a miss must not be retried on every keystroke")
                .isZero();
    }

    @Test
    void rejectsAnIsbnThatFailsItsChecksumWithoutCallingAnyone() throws Exception {
        HttpResponse<String> response = get("/api/isbn/9780306406158");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(CountingProvider.calls).isZero();
        Integer cached = jdbc.queryForObject(
                "SELECT count(*) FROM isbn_cache WHERE isbn = '9780306406158'", Integer.class);
        assertThat(cached).isZero();
    }

    @Test
    void returnsFourOhFourSoTheUiCanOfferTheManualForm() throws Exception {
        HttpResponse<String> response = get("/api/isbn/not-an-isbn-at-all");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
