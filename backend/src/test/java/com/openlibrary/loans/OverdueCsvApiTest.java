package com.openlibrary.loans;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * Calling twenty people needs a list you can work down: in a spreadsheet, or
 * printed. A CSV that Excel mangles is worse than none, because people trust it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OverdueCsvApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
    private HttpTestClient clerk;
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
        deleteGraph();

        librarian = signedIn(DemoUsers.LIBRARIAN_EMAIL, DemoUsers.LIBRARIAN_PASSWORD);
        clerk = signedIn(DemoUsers.CLERK_EMAIL, DemoUsers.CLERK_PASSWORD);

        // A title with the two things that break a naive CSV: a comma and a quote.
        var book = clerk.post("/catalog/books", Map.of(
                "title", "Cien años, de \"sol\"",
                "isbn", "9780307474728",
                "authors", List.of(Map.of("name", "Gabriel García Márquez", "role", "AUTOR"))));
        assertThat(book.status()).as("book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));
    }

    private void deleteGraph() {
        jdbc.update("delete from book_authors");
        jdbc.update("delete from books");
        jdbc.update("delete from categories");
        jdbc.update("delete from authors");
    }

    private HttpTestClient signedIn(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login %s", email).isEqualTo(200);
        return client;
    }

    private Long lendTo(String email, String name, int dueInDays) {
        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update set full_name = excluded.full_name",
                email, passwords.encode(DemoUsers.READER_PASSWORD), name, Role.LECTOR.name());
        long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);

        var stock = librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        long copyId = stock.json().path("created").get(0).path("id").asLong();
        var lent = librarian.post("/loans", Map.of("copyId", copyId, "readerId", userId));
        assertThat(lent.status()).as("lend: %s", lent.body()).isEqualTo(201);
        jdbc.update("update loans set due_at = now() + (? || ' days')::interval"
                + " where copy_id = ? and returned_at is null", dueInDays, copyId);
        return copyId;
    }

    private HttpTestClient.Result csv(String path) {
        return librarian.get(path);
    }

    @Test
    void startsWithTheByteOrderMarkSoExcelReadsTheAccents() {
        lendTo("ana.csv@demo.test", "Ana Csv", -3);

        var result = csv("/loans/overdue.csv");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).startsWith("\uFEFF");
        assertThat(result.body()).contains("Ana Csv");
    }

    @Test
    void onlyTheOverdueOnes() {
        lendTo("tarde.csv@demo.test", "Tarde", -3);
        lendTo("pronto.csv@demo.test", "Pronto", 10);

        var body = csv("/loans/overdue.csv").body();

        assertThat(body).contains("Tarde");
        assertThat(body).doesNotContain("Pronto");
    }

    @Test
    void quotesCommasAndQuotesInTitles() {
        lendTo("comillas.csv@demo.test", "Comillas", -1);

        var body = csv("/loans/overdue.csv").body();

        // Naive concatenation would split the title across three columns here.
        assertThat(body).contains("\"Cien años, de \"\"sol\"\"\"");
    }

    @Test
    void aCellThatLooksLikeAFormulaCannotRun() {
        jdbc.update("insert into users (email, password_hash, full_name, role, must_change_password)"
                        + " values (?, ?, ?, ?, false)"
                        + " on conflict (lower(email)) do update set full_name = excluded.full_name",
                "formula.csv@demo.test", passwords.encode(DemoUsers.READER_PASSWORD),
                "=HYPERLINK(\"http://malo\")", Role.LECTOR.name());
        long userId = jdbc.queryForObject(
                "select id from users where email = 'formula.csv@demo.test'", Long.class);
        var stock = librarian.post("/inventory/copies/bulk", Map.of("bookId", bookId, "quantity", 1));
        long copyId = stock.json().path("created").get(0).path("id").asLong();
        librarian.post("/loans", Map.of("copyId", copyId, "readerId", userId));
        jdbc.update("update loans set due_at = now() - interval '2 days'"
                + " where copy_id = ? and returned_at is null", copyId);

        var body = csv("/loans/overdue.csv").body();

        // Excel would happily run this the moment somebody clicks the cell.
        assertThat(body).doesNotContain(",=HYPERLINK");
        assertThat(body).contains("'=HYPERLINK");
    }

    @Test
    void saysHowManyDaysLateSoThePhoneListIsPrioritised() {
        lendTo("urgente.csv@demo.test", "Urgente", -9);

        var body = csv("/loans/overdue.csv").body();

        assertThat(body).contains("9");
        assertThat(body).contains("lector,correo,libro,ejemplar,vencido,dias_de_retraso");
    }

    @Test
    void anEmptyListIsAHeaderAndNothingElse() {
        var body = csv("/loans/overdue.csv").body();

        // The BOM, the header, and no rows at all.
        assertThat(body).isEqualTo(
                "\uFEFFlector,correo,libro,ejemplar,vencido,dias_de_retraso\r\n");
    }

    @Test
    void aReaderCannotDownloadTheDebtList() {
        var reader = signedIn(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);

        assertThat(reader.get("/loans/overdue.csv").status()).isEqualTo(403);
    }

    @Test
    void anAdministratorCan() {
        var admin = signedIn(DemoUsers.ADMIN_EMAIL, DemoUsers.ADMIN_PASSWORD);
        lendTo("admin.csv@demo.test", "Admin", -1);

        assertThat(admin.get("/loans/overdue.csv").status()).isEqualTo(200);
    }
}