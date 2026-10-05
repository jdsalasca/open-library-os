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
 * The loan state machine over real HTTP: borrow, renew, return, plus the rules
 * that must refuse and the reservations queue in between.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient librarian;
    private Long bookId;
    private Long readerId;
    private Long otherReaderId;

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

        librarian = signedIn(Role.BIBLIOTECARIO);
        var clerk = signedIn(Role.ADMINISTRATIVO);
        var reader = signedIn(Role.LECTOR);
        var victim = signedInAs(DemoUsers.VICTIM_EMAIL, DemoUsers.VICTIM_PASSWORD);

        var book = clerk.post("/catalog/books", Map.of(
                "title", "Neuromante",
                "isbn", "9788491058106",
                "authors", List.of(Map.of("name", "William Gibson", "role", "AUTOR"))));
        assertThat(book.status()).as("seed book: %s", book.body()).isEqualTo(201);
        bookId = Long.valueOf(book.text("id"));

        readerId = idOf(reader, DemoUsers.READER_EMAIL);
        otherReaderId = idOf(victim, DemoUsers.VICTIM_EMAIL);
    }

    private Long idOf(HttpTestClient client, String email) {
        var me = client.get("/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("email")).isEqualTo(email);
        return Long.valueOf(me.text("id"));
    }

    private HttpTestClient signedIn(Role role) {
        return signedInAs(DemoUsers.emailFor(role), DemoUsers.passwordFor(DemoUsers.emailFor(role)));
    }

    /** Two readers are needed for the queue, and role maps to one email only. */
    private HttpTestClient signedInAs(String email, String password) {
        var client = new HttpTestClient(port);
        assertThat(client.post("/auth/login", Map.of("email", email, "password", password)).status())
                .as("login as %s", email).isEqualTo(200);
        return client;
    }

    private long stockCopies(int howMany) {
        var created = librarian.post("/inventory/copies/bulk",
                Map.of("bookId", bookId, "quantity", howMany));
        assertThat(created.status()).as("stock: %s", created.body()).isEqualTo(201);
        return created.json().path("created").size();
    }

    private long firstCopyId() {
        return jdbc.queryForObject("select min(id) from copies", Long.class);
    }

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    void borrowsReturnsAndFreesTheCopyAgain() {
        stockCopies(2);
        long copyId = firstCopyId();

        var loan = librarian.post("/loans", Map.of("copyId", copyId, "readerId", readerId));
        assertThat(loan.status()).as("borrow: %s", loan.body()).isEqualTo(201);
        // Jackson omits null fields, so an open loan simply has no returnedAt key.
        assertThat(loan.json().has("returnedAt")).isFalse();
        assertThat(loan.json().path("copyCode").asText()).startsWith("OL-");
        assertThat(copyStatus(copyId)).isEqualTo("PRESTADO");

        var given = librarian.post("/loans/" + loan.text("id") + "/return", Map.of());
        assertThat(given.status()).as("return: %s", given.body()).isEqualTo(200);
        assertThat(given.json().path("returnedAt").isNull()).isFalse();
        assertThat(copyStatus(copyId)).isEqualTo("DISPONIBLE");
    }

    @Test
    void renewsPushingTheDueDateOut() {
        stockCopies(1);
        long copyId = firstCopyId();
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", copyId, "readerId", readerId)).text("id"));
        var dueBefore = Instant_hours(firstLoan().path("dueAt").asText());

        var renewed = librarian.post("/loans/" + loanId + "/renew", Map.of());

        assertThat(renewed.status()).as("renew: %s", renewed.body()).isEqualTo(200);
        assertThat(renewed.json().path("renewals").asInt()).isEqualTo(1);
        assertThat(Instant_hours(renewed.json().path("dueAt").asText())).isGreaterThan(dueBefore);
    }

    // ── the rules ────────────────────────────────────────────────────────────

    @Test
    void refusesASecondLoanOfTheSameCopy() {
        stockCopies(1);
        long copyId = firstCopyId();
        librarian.post("/loans", Map.of("copyId", copyId, "readerId", readerId));

        var again = librarian.post("/loans", Map.of("copyId", copyId, "readerId", otherReaderId));

        assertThat(again.status()).isEqualTo(422);
        assertThat(again.text("code")).isEqualTo("copy_not_available");
    }

    @Test
    void refusesBeyondTheReadersLimit() {
        jdbc.update("update app_config set value = '1' where key = 'loans.max_active_per_reader'");
        stockCopies(2);
        long first = firstCopyId();
        long second = jdbc.queryForObject("select max(id) from copies", Long.class);
        librarian.post("/loans", Map.of("copyId", first, "readerId", readerId));

        var refused = librarian.post("/loans", Map.of("copyId", second, "readerId", readerId));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("reader_limit_reached");
    }

    @Test
    void refusesWhenSomebodyElseIsWaitingForTheBook() {
        stockCopies(2);
        long copyId = firstCopyId();
        // A busy evening: the shelf is empty, so a reader reserves. Then a copy
        // comes back and the desk cannot lend it to somebody who is not waiting.
        lendEveryCopy();
        var victim = signedInAs(DemoUsers.VICTIM_EMAIL, DemoUsers.VICTIM_PASSWORD);
        assertThat(victim.post("/loans/reservations", Map.of("bookId", bookId)).status())
                .isEqualTo(201);

        returnCopy(copyId);
        var refused = librarian.post("/loans", Map.of("copyId", copyId, "readerId", readerId));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("book_reserved_by_other_reader");
    }

    @Test
    void lendingToTheReaderWhoWasWaitingClosesTheirReservation() {
        stockCopies(2);
        long copyId = firstCopyId();
        lendEveryCopy();
        var waiting = signedInAs(DemoUsers.VICTIM_EMAIL, DemoUsers.VICTIM_PASSWORD);
        long waitingId = idOf(waiting, DemoUsers.VICTIM_EMAIL);
        assertThat(waiting.post("/loans/reservations", Map.of("bookId", bookId)).status())
                .isEqualTo(201);

        returnCopy(copyId);
        var loan = librarian.post("/loans", Map.of("copyId", copyId, "readerId", waitingId));

        assertThat(loan.status()).as("borrow: %s", loan.body()).isEqualTo(201);
        Integer open = jdbc.queryForObject(
                "select count(*) from reservations where fulfilled_at is null", Integer.class);
        assertThat(open).isZero();
    }

    /**
     * Empties the shelf. The copies go to {@code readerId} on purpose: the reader
     * who waits in the queue cannot be the one holding every copy.
     */
    /** A copy comes back to the shelf through the desk, loan row and all. */
    private void returnCopy(long copyId) {
        var loan = jdbc.queryForObject(
                "select id from loans where copy_id = ? and returned_at is null", Long.class, copyId);
        assertThat(librarian.post("/loans/" + loan + "/return", Map.of()).status())
                .as("return copy %s", copyId).isEqualTo(200);
    }

    private void lendEveryCopy() {
        for (Long copy : jdbc.queryForList("select id from copies order by id", Long.class)) {
            librarian.post("/loans", Map.of("copyId", copy, "readerId", readerId));
        }
    }

    @Test
    void refusesToRenewBeyondTheConfiguredCap() {
        jdbc.update("update app_config set value = '0' where key = 'loans.max_renewals'");
        stockCopies(1);
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", readerId)).text("id"));

        var refused = librarian.post("/loans/" + loanId + "/renew", Map.of());

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.text("code")).isEqualTo("renewal_limit_reached");
    }

    @Test
    void refusesToReturnTwice() {
        stockCopies(1);
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", readerId)).text("id"));
        librarian.post("/loans/" + loanId + "/return", Map.of());

        var again = librarian.post("/loans/" + loanId + "/return", Map.of());

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("loan_not_active");
    }

    // ── queue and history ────────────────────────────────────────────────────

    @Test
    void keepsTheReservationQueueInCreationOrder() {
        var first = signedInAs(DemoUsers.READER_EMAIL, DemoUsers.READER_PASSWORD);
        var second = signedInAs(DemoUsers.VICTIM_EMAIL, DemoUsers.VICTIM_PASSWORD);
        long firstId = Long.parseLong(
                first.post("/loans/reservations", Map.of("bookId", bookId)).text("id"));
        long secondId = Long.parseLong(
                second.post("/loans/reservations", Map.of("bookId", bookId)).text("id"));
        // Two POSTs can land on the same timestamp, which would make the order a
        // coin flip: give them a deliberate hour apart so the assertion is about
        // the ordering rule and not about clock resolution.
        jdbc.update("update reservations set created_at = now() - interval '1 hour' where id = ?",
                firstId);
        jdbc.update("update reservations set created_at = now() where id = ?", secondId);

        var queue = librarian.get("/loans/queue");

        assertThat(queue.status()).isEqualTo(200);
        assertThat(queue.json()).hasSize(2);
        assertThat(queue.json().get(0).path("id").asLong()).isEqualTo(firstId);
        assertThat(queue.json().get(1).path("id").asLong()).isEqualTo(secondId);
    }

    @Test
    void refusesASecondReservationOfTheSameBook() {
        var reader = signedIn(Role.LECTOR);
        assertThat(reader.post("/loans/reservations", Map.of("bookId", bookId)).status())
                .isEqualTo(201);

        var again = reader.post("/loans/reservations", Map.of("bookId", bookId));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.text("code")).isEqualTo("already_reserved");
    }

    @Test
    void cancellingTakesTheReaderOutOfTheQueue() {
        var reader = signedIn(Role.LECTOR);
        long id = Long.parseLong(
                reader.post("/loans/reservations", Map.of("bookId", bookId)).text("id"));

        assertThat(reader.delete("/loans/reservations/" + id).status()).isEqualTo(204);

        Integer open = jdbc.queryForObject("""
                select count(*) from reservations
                where book_id = ? and cancelled_at is null
                """, Integer.class, bookId);
        assertThat(open).isZero();
    }

    @Test
    void theDeskListHidesReturnedLoansUntilHistoryIsAskedFor() {
        stockCopies(2);
        long first = firstCopyId();
        long second = jdbc.queryForObject("select max(id) from copies", Long.class);
        long kept = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", first, "readerId", readerId)).text("id"));
        long closed = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", second, "readerId", otherReaderId)).text("id"));
        librarian.post("/loans/" + closed + "/return", Map.of());

        // The screen used to say "en plazo" about a book already back on the shelf.
        var open = librarian.get("/loans");
        assertThat(open.json().path("totalElements").asInt()).isEqualTo(1);
        assertThat(open.json().path("content").get(0).path("id").asLong()).isEqualTo(kept);

        var history = librarian.get("/loans?state=CLOSED");
        assertThat(history.json().path("totalElements").asInt()).isEqualTo(1);
        assertThat(history.json().path("content").get(0).path("id").asLong()).isEqualTo(closed);

        var both = librarian.get("/loans?state=ALL");
        assertThat(both.json().path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void aReturnedLoanIsNotFlaggedOverdue() {
        stockCopies(1);
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", readerId)).text("id"));
        jdbc.update("update loans set due_at = now() - interval '5 days' where id = ?", loanId);
        librarian.post("/loans/" + loanId + "/return", Map.of());

        assertThat(librarian.get("/loans").json().path("totalElements").asInt()).isZero();
        assertThat(librarian.get("/loans?state=CLOSED")
                .json().path("content").get(0).path("overdue").asBoolean()).isFalse();
    }

    @Test
    void refusesAReaderNumberThatDoesNotExist() {
        stockCopies(1);

        // Found by the round 4 screenshots: a mistyped reader id came back as a
        // 500 from the foreign key instead of a message the desk can act on.
        var refused = librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", 999999L));

        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(404);
        assertThat(refused.text("code")).isEqualTo("reader_not_found");
        Integer loans = jdbc.queryForObject("select count(*) from loans", Integer.class);
        assertThat(loans).isZero();
    }

    @Test
    void aReaderOnlySeesTheirOwnLoans() {        stockCopies(2);
        long mine = firstCopyId();
        long theirs = jdbc.queryForObject("select max(id) from copies", Long.class);
        librarian.post("/loans", Map.of("copyId", mine, "readerId", readerId));
        librarian.post("/loans", Map.of("copyId", theirs, "readerId", otherReaderId));

        var mineOnly = librarian.get("/loans?readerId=" + readerId);

        assertThat(mineOnly.json().path("totalElements").asInt()).isEqualTo(1);
        assertThat(mineOnly.json().path("content").get(0).path("readerId").asLong())
                .isEqualTo(readerId);
    }

    @Test
    void aReaderCannotRunTheDesk() {
        var reader = signedIn(Role.LECTOR);
        stockCopies(1);

        assertThat(reader.get("/loans").status()).isEqualTo(403);
        assertThat(reader.post("/loans", Map.of(
                "copyId", firstCopyId(), "readerId", readerId)).status()).isEqualTo(403);
    }

    @Test
    void listsThePolicySettingsFromConfiguration() {
        jdbc.update("update app_config set value = '10' where key = 'loans.days_default'");
        jdbc.update("update app_config set value = '3' where key = 'loans.max_active_per_reader'");
        jdbc.update("update app_config set value = '1' where key = 'loans.max_renewals'");

        var settings = librarian.get("/loans/settings");

        assertThat(settings.json().path("loanDays").asInt()).isEqualTo(10);
        assertThat(settings.json().path("readerLimit").asInt()).isEqualTo(3);
        assertThat(settings.json().path("maxRenewals").asInt()).isEqualTo(1);
    }

    @Test
    void theDueDateFollowsTheConfiguredDays() {
        jdbc.update("update app_config set value = '7' where key = 'loans.days_default'");
        stockCopies(1);

        var loan = librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", readerId));

        var due = java.time.Instant.parse(loan.json().path("dueAt").asText());
        var borrowed = java.time.Instant.parse(loan.json().path("borrowedAt").asText());
        assertThat(java.time.Duration.between(borrowed, due).toDays()).isEqualTo(7);
    }

    @Test
    void anOverdueLoanBlocksTheReader() {
        stockCopies(2);
        long first = firstCopyId();
        long second = jdbc.queryForObject("select max(id) from copies", Long.class);
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", first, "readerId", readerId)).text("id"));
        jdbc.update("update loans set due_at = now() - interval '3 days' where id = ?", loanId);

        var refused = librarian.post("/loans", Map.of("copyId", second, "readerId", readerId));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("code")).isEqualTo("reader_has_overdue_loans");
    }

    @Test
    void marksTheOverdueLoansOnTheDeskList() {
        stockCopies(1);
        long loanId = Long.parseLong(librarian.post("/loans",
                Map.of("copyId", firstCopyId(), "readerId", readerId)).text("id"));

        assertThat(firstLoan().path("overdue").asBoolean()).isFalse();

        jdbc.update("update loans set due_at = now() - interval '1 day' where id = ?", loanId);

        assertThat(firstLoan().path("overdue").asBoolean()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The newest loan as the desk list shows it. */
    private tools.jackson.databind.JsonNode firstLoan() {
        return librarian.get("/loans").json().path("content").get(0);
    }

    private String copyStatus(long copyId) {
        return jdbc.queryForObject("select status from copies where id = ?", String.class, copyId);
    }

    private static long Instant_hours(String isoInstant) {
        return java.time.Instant.parse(isoInstant).toEpochMilli();
    }
}
