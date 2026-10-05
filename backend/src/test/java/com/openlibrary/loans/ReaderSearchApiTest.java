package com.openlibrary.loans;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import com.openlibrary.PostgresTest;
import com.openlibrary.auth.Role;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * The desk has to find a reader by name or card number, not by a database id: a
 * librarian has a person in front of them, not a number.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReaderSearchApiTest extends PostgresTest {

    private static final String BRUNO_EMAIL = "bruno.garcia@demo.test";
    private static final String BRUNO_NAME = "Bruno García Ordóñez";

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
    private Long brunoId;
    private Long bookId;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        jdbc.update("delete from reservations");
        jdbc.update("delete from loans");
        jdbc.update("delete from copy_moves");
        jdbc.update("delete from copies");
        jdbc.update("delete from locations");
        jdbc.update("delete from book_categories");
        jdbc.update("delete from book_authors");
        jdbc.update("delete from books");
        jdbc.update("delete from categories");
        jdbc.update("delete from authors");

        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        var clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);

        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update"
                        + " set full_name = excluded.full_name, active = true",
                BRUNO_EMAIL, passwords.encode(DemoUsers.READER_PASSWORD),
                BRUNO_NAME, Role.LECTOR.name());
        brunoId = jdbc.queryForObject("select id from users where email = ?", Long.class, BRUNO_EMAIL);

        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromante",
                "isbn", "9788491058106",
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private void lendToBruno(int howMany) {
        var created = librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", howMany));
        assertThat(created.status()).as("stock: %s", created.body()).isEqualTo(201);
        created.json().path("created").forEach(copy ->
                assertThat(librarian.post("/loans",
                        Map.of("copyId", copy.path("id").asLong(), "readerId", brunoId)).status())
                        .as("lend to Bruno").isEqualTo(201));
    }

    @Test
    void findsAReaderByNameWithoutTheAccentsGettingInTheWay() {
        var found = librarian.get("/loans/readers?q=bruno");

        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json()).isNotEmpty();
        assertThat(found.json().get(0).path("fullName").asText()).isEqualTo(BRUNO_NAME);
    }

    @Test
    void findsAReaderWhenTheDeskTypesTheNameWithoutTheAccent() {
        // What the librarian can type at the counter with one hand.
        var found = librarian.get("/loans/readers?q=garcia");

        assertThat(found.json().get(0).path("email").asText()).isEqualTo(BRUNO_EMAIL);
    }

    @Test
    void findsAReaderByEmailWhateverTheCaseIs() {
        var found = librarian.get("/loans/readers?q=BRUNO.GARCIA@DEMO.TEST");

        assertThat(found.json().get(0).path("email").asText()).isEqualTo(BRUNO_EMAIL);
    }

    @Test
    void countsWhatTheReaderAlreadyHasAndWhatIsLate() {
        lendToBruno(2);
        var loan = jdbc.queryForObject("select id from loans order by id limit 1", Long.class);
        jdbc.update("update loans set due_at = now() - interval '4 days' where id = ?", loan);

        var reader = librarian.get("/loans/readers?q=bruno").json().get(0);

        assertThat(reader.path("activeLoans").asInt()).isEqualTo(2);
        assertThat(reader.path("overdue").asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyQueryAsksTheLibrarianToTypeSomething() {
        // No query, no list: a bare "show me every account" is not what the desk needs.
        var found = librarian.get("/loans/readers");

        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json()).isEmpty();
    }

    @Test
    void doesNotOfferDeactivatedAccounts() {
        jdbc.update("update users set active = false where email = ?", BRUNO_EMAIL);

        assertThat(librarian.get("/loans/readers?q=bruno").json()).isEmpty();
    }

    @Test
    void neverReturnsPasswordHashesOrRoleColumns() {
        librarian.get("/loans/readers?q=a").json().forEach(reader -> {
            assertThat(reader.has("passwordHash")).isFalse();
            assertThat(reader.has("mustChangePassword")).isFalse();
            assertThat(reader.has("role")).isFalse();
        });
    }

    @Test
    void aReaderCannotLookUpOtherReaders() {
        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);

        assertThat(reader.get("/loans/readers?q=bruno").status()).isEqualTo(403);
    }

    @Test
    void anAdministratorCanUseItToo() {
        var admin = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD);

        assertThat(admin.get("/loans/readers?q=bruno").status()).isEqualTo(200);
    }
}
