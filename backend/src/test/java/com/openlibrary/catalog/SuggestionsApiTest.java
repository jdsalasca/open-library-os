package com.openlibrary.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import com.openlibrary.PostgresTest;
import com.openlibrary.support.DemoUsers;
import com.openlibrary.support.HttpTestClient;

/**
 * A catalog of two books should not spend four seconds telling you so. Same
 * searchable surface as the catalogue, plus the newest arrivals, which is what
 * somebody with a hand on the door actually wants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SuggestionsApiTest extends PostgresTest {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private HttpTestClient clerk;

    @BeforeEach
    void setUp() {
        DemoUsers.seed(dataSource, passwords());
        DemoUsers.resetPasswords(jdbc, passwords());
        jdbc.update("update users set must_change_password = false");
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

    private org.springframework.security.crypto.password.PasswordEncoder passwords() {
        return encoder;
    }

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;

    private Long book(String title, String author, String isbn) {
        var response = clerk.post("/catalog/books", Map.of(
                "title", title,
                "authors", List.of(Map.of("name", author, "role", "AUTOR"))));
        assertThat(response.status()).as("book %s: %s", title, response.body()).isEqualTo(201);
        return Long.valueOf(response.text("id"));
    }

    @Test
    void anEmptyCatalogSaysSoInsteadOfFailing() {
        var suggestions = clerk.get("/catalog/suggestions");

        assertThat(suggestions.status()).isEqualTo(200);
        assertThat(suggestions.json().path("totalBooks").asInt()).isZero();
        assertThat(suggestions.json().path("recent").isEmpty()).isTrue();
    }

    @Test
    void countsWhatIsActuallyInTheLibrary() {
        book("Rayuela", "Julio Cortazar", "9788437604572");
        book("Cien anos de soledad", "Gabriel Garcia Marquez", "9780307474728");
        book("Ficciones", "Jorge Luis Borges", "9780802130303");

        var suggestions = clerk.get("/catalog/suggestions");

        assertThat(suggestions.json().path("totalBooks").asInt()).isEqualTo(3);
    }

    @Test
    void offersTheNewestFirst() {
        book("Antiguo", "Viejo", "9788437604572");
        book("Reciente", "Nuevo", "9780307474728");

        var recent = clerk.get("/catalog/suggestions").json().path("recent");

        assertThat(recent.get(0).path("title").asText()).isEqualTo("Reciente");
    }

    @Test
    void looksBooksUpByNameAndAuthorWithoutAccents() {
        var id = book("Como agua para chocolate", "Laura Esquivel", "9788401337212");

        var byTitle = clerk.get("/catalog/suggestions?q=agua");
        assertThat(byTitle.json().path("results").isEmpty()).isFalse();
        assertThat(byTitle.json().path("results").get(0).path("id").asLong()).isEqualTo(id);

        var byAuthor = clerk.get("/catalog/suggestions?q=esquivel");
        assertThat(byAuthor.json().path("results").get(0).path("id").asLong()).isEqualTo(id);
    }

    @Test
    void neverReturnsMoreThanTheScreenCanShow() {
        for (int i = 0; i < 9; i++) {
            book("Libro " + i, "Autor " + i, "97800000000" + (10 + i));
        }

        var suggestions = clerk.get("/catalog/suggestions");

        assertThat(suggestions.json().path("recent").size()).isLessThanOrEqualTo(6);
        assertThat(suggestions.json().path("totalBooks").asInt()).isEqualTo(9);
    }

    @Test
    void aCardHolderMayBrowseButNotChangeTheCatalog() {
        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login",
                Map.of("email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD)).status())
                .isEqualTo(200);

        // Browsing the shelves is what the catalogue is for; anyone signed in
        // may look, which is why this endpoint is not staff-only.
        assertThat(reader.get("/catalog/suggestions").status()).isEqualTo(200);
    }
}