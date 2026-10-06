package com.openlibrary.catalog;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * The first day of a self-hosted library is not an empty catalogue: it is three
 * hundred titles somebody already owns, in a spreadsheet. Restoring a full backup
 * does not help with that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogueCsvImportApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    private HttpTestClient clerk;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords);
        DemoUsers.resetPasswords(jdbc, passwords);
        jdbc.update("update users set must_change_password = false");
        jdbc.update("delete from reservations");
        jdbc.update("delete from loans");
        jdbc.update("delete from copy_moves");
        jdbc.update("delete from copies");
        jdbc.update("delete from book_categories");
        jdbc.update("delete from book_authors");
        jdbc.update("delete from books");
        jdbc.update("delete from categories");
        jdbc.update("delete from authors");

        clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login",
                Map.of("email", DemoUsers.CLERK_EMAIL, "password", DemoUsers.CLERK_PASSWORD)).status())
                .isEqualTo(200);
    }

    private HttpTestClient.Result upload(String csv) {
        return clerk.postRaw("/catalog/import", csv, "text/csv");
    }

    @Test
    void loadsTitlesWithAuthorsAndIsbn() {
        var result = upload("""
                titulo,autor,isbn13,editorial,anio
                Neuromancer,William Gibson,9780306406157,Ace,1984
                Rayuela,Julio Cortazar,,,1963
                """);

        assertThat(result.status()).as("body: %s", result.body()).isEqualTo(200);
        assertThat(result.json().path("created").asInt()).isEqualTo(2);
        assertThat(result.json().path("failed").asInt()).isZero();
        assertThat(bookCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from books where isbn13 = '9780306406157'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void acceptsAccentsAndLowerCaseHeadersInSpanish() {
        var result = upload("""
                Título;Autor;ISBN
                La biblioteca de medievo;A. Reynolds;9788491058106
                """);

        assertThat(result.status()).as("body: %s", result.body()).isEqualTo(200);
        assertThat(result.json().path("created").asInt()).isEqualTo(1);
        assertThat(titleOf("9788491058106")).isEqualTo("La biblioteca de medievo");
    }

    @Test
    void addsTheCopiesInTheSamePass() {
        var result = upload("""
                titulo,autor,isbn13,copias
                Dune,Frank Herbert,9780441013593,3
                """);

        assertThat(result.json().path("created").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from copies c join books b on b.id = c.book_id"
                        + " where b.isbn13 = '9780441013593'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void reimportingTheSameFileDoesNotDuplicateAnything() {
        String csv = """
                titulo,autor,isbn13
                Neuromancer,William Gibson,9780306406157
                """;
        assertThat(upload(csv).json().path("created").asInt()).isEqualTo(1);

        var second = upload(csv);
        assertThat(second.status()).as("body: %s", second.body()).isEqualTo(200);

        // Same ISBN: one update, no second book, no duplicates.
        assertThat(second.json().path("created").asInt()).as("body: %s", second.body()).isZero();
        assertThat(second.json().path("updated").asInt()).as("body: %s", second.body()).isEqualTo(1);
        assertThat(bookCount()).isEqualTo(1);
    }

    /**
 * The copies column says how many the library owns, not how many to print. An import
 * that added them every time would hand out another label per copy each time the file
 * was uploaded, and nobody would notice until a stock count.
 */
@Test
void reimportingDoesNotPrintMoreLabels() {
        String csv = """
                titulo,autor,isbn13,copias
                Dune,Frank Herbert,9780441013593,3
                """;
        assertThat(upload(csv).json().path("copiesAdded").asInt()).isEqualTo(3);

        var second = upload(csv);

        assertThat(second.status()).as("body: %s", second.body()).isEqualTo(200);
        assertThat(second.json().path("copiesAdded").asInt())
                .as("the shelf already has three").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from copies c join books b on b.id = c.book_id"
                        + " where b.isbn13 = '9780441013593'", Integer.class))
                .isEqualTo(3);
    }

@Test
void aHigherNumberStillTopsTheShelfUp() {
        assertThat(upload("titulo,autor,isbn13,copias\nDune,Frank Herbert,9780441013593,2\n")
                .json().path("copiesAdded").asInt()).isEqualTo(2);

        assertThat(upload("titulo,autor,isbn13,copias\nDune,Frank Herbert,9780441013593,5\n")
                .json().path("copiesAdded").asInt())
                .as("only the three missing ones").isEqualTo(3);
    }

    @Test
    void oneBadRowDoesNotLoseTheGoodOnes() {
        var result = upload("""
                titulo,autor,isbn13
                Neuromancer,William Gibson,9780306406157
                Sin ISBN ni forma de identificarlo,,
                Rayuela,Julio Cortazar,no-es-un-isbn
                """);

        assertThat(result.json().path("created").asInt()).isEqualTo(1);
        assertThat(result.json().path("failed").asInt()).isEqualTo(2);
        // The desk has to know WHICH row to fix, so the report carries the line.
        assertThat(result.body()).contains("3");
        assertThat(result.body()).contains("4");
    }

    @Test
    void anEmptyFileIsAFileThatSaysNothing() {
        var result = upload("titulo,autor,isbn13\n");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().path("created").asInt()).isZero();
        assertThat(result.json().path("failed").asInt()).isZero();
    }

    @Test
    void aReaderCannotLoadTheCatalogue() {
        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login",
                Map.of("email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD)).status())
                .isEqualTo(200);

        assertThat(reader.postRaw("/catalog/import",
                "titulo,autor\nX,Y\n", "text/csv").status()).isEqualTo(403);
    }

    private int bookCount() {
        return jdbc.queryForObject("select count(*) from books", Integer.class);
    }

    private String titleOf(String isbn) {
        return jdbc.queryForObject("select title from books where isbn13 = ?", String.class, isbn);
    }
}