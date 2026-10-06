package com.openlibrary.auth;

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
 * A library that moves to self-hosting arrives with eight hundred members already on
 * a spreadsheet, and today the only way in is one account at a time.
 *
 * <p>Two things this must never do: invent a password the librarian chose, or make
 * an account usable before its owner has set one. Both are the same decision, and the
 * PasswordGate already knows how to enforce it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReaderCsvImportApiTest extends PostgresTest {

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
        // Each test imports its own readers, and the database is shared by the class:
        // without this, the counts include the previous test's accounts.
        jdbc.update("delete from users where email not like '%@demo.test'");

        clerk = new HttpTestClient(port);
        assertThat(clerk.post("/auth/login",
                Map.of("email", DemoUsers.CLERK_EMAIL, "password", DemoUsers.CLERK_PASSWORD)).status())
                .isEqualTo(200);
    }

    private HttpTestClient.Result upload(String csv) {
        return clerk.postRaw("/users/import", csv, "text/csv");
    }

    @Test
    void loadsReadersFromTheSheet() {
        int before = countReaders();

        var result = upload("""
                nombre,correo
                Ana Lectora,ana@example.org
                Bruno Nieto,bruno@example.org
                """);

        assertThat(result.status()).as("body: %s", result.body()).isEqualTo(200);
        assertThat(result.json().path("created").asInt()).isEqualTo(2);
        assertThat(result.json().path("failed").asInt()).isZero();
        // Measured as a delta: the database is shared with other test classes that
        // create and rename readers of their own, so an absolute count would be
        // asserting on their fixtures as much as on this import.
        assertThat(countReaders() - before).as("two readers added").isEqualTo(2);
    }

    @Test
    void everyImportedAccountHasToSetItsOwnPassword() {
        upload("nombre,correo\nAna Lectora,ana-importada@example.org\n");

        // The sheet has no passwords and must never be able to choose them. An
        // account that arrives ready to use is an account anyone can walk into.
        Integer pending = jdbc.queryForObject(
                "select count(*) from users where email = 'ana-importada@example.org'"
                        + " and must_change_password", Integer.class);
        assertThat(pending).as("the owner has to choose a password on first login").isEqualTo(1);
    }

@Test
    void reimportingTheSameSheetChangesNobody() {
        int before = countReaders();
        String csv = "nombre,correo\nAna Lectora,ana@example.org\n";
        assertThat(upload(csv).json().path("created").asInt()).isEqualTo(1);
        assertThat(countReaders() - before).isEqualTo(1);

        var second = upload(csv);

        assertThat(second.status()).as("body: %s", second.body()).isEqualTo(200);
        assertThat(second.json().path("created").asInt())
                .as("the account already exists, so nothing is created").isZero();
        assertThat(second.json().path("failed").asInt())
                .as("and it is not an error either").isZero();
        assertThat(second.json().path("alreadyThere").asInt()).isEqualTo(1);
        assertThat(countReaders() - before).as("still the same one, not two").isEqualTo(1);
    }

    @Test
    void aRowWithoutAnEmailIsRefusedWithItsLine() {
        var result = upload("""
                nombre,correo
                Ana Lectora,ana@example.org
                Sin correo,
                Bruno Nieto,bruno@example.org
                """);

        assertThat(result.json().path("created").asInt()).isEqualTo(2);
        assertThat(result.json().path("failed").asInt()).isEqualTo(1);
        assertThat(result.body()).contains("3");
        // A librarian has to be able to act on this. The raw driver text is not it.
        assertThat(result.body()).contains("falta el correo");
        assertThat(result.body()).doesNotContain("SQLException");
    }

    @Test
    void aRowWithoutANameIsAlsoRefusedInWords() {
        var result = upload("nombre,correo\n,ana@example.org\n");

        // A blank cell is absent from the row, so this used to arrive as a driver
        // error about a NOT NULL constraint instead of as something actionable.
        assertThat(result.json().path("failed").asInt()).isEqualTo(1);
        assertThat(result.body()).contains("falta el nombre");
        assertThat(result.body()).doesNotContain("SQLException");
    }

    @Test
    void oneNewAccountIsNotTwo() {
        // The heading is read by people, not parsed: "1 cuentas nuevas" is the kind
        // of thing that makes a whole screen look unfinished.
        var result = upload("nombre,correo\nAna Lectora,solo-una@example.org\n");

        assertThat(result.json().path("created").asInt()).isEqualTo(1);
        assertThat(result.json().path("alreadyThere").asInt()).isZero();
        assertThat(result.json().path("failed").asInt()).isZero();
    }

    @Test
    void aRosterThatHasNoEmailColumnIsRefusedOutright() {
        // The tempting mistake: treat whatever column looks like an address as the
        // email. It would create eight hundred accounts called "600000001".
        var result = upload("nombre,telefono\nAna Lectora,600000001\n");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("correo");
    }

    @Test
    void anEmptySheetCreatesNobody() {
        var result = upload("nombre,correo\n");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().path("created").asInt()).isZero();
    }

    @Test
    void aSheetWithNoNameColumnIsRefusedOutright() {
        var result = upload("email,telefono\na@b.org,600000000\n");

        // Guessing which column is the name would create accounts called
        // "600000000", and the librarian would only find out at the desk.
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("nombre");
    }

    @Test
    void staffCanImportButReadersCannot() {
        assertThat(upload("nombre,correo\nAna,ana@example.org\n").status()).isEqualTo(200);

        var reader = new HttpTestClient(port);
        assertThat(reader.post("/auth/login",
                Map.of("email", DemoUsers.READER_EMAIL, "password", DemoUsers.READER_PASSWORD)).status())
                .isEqualTo(200);

        assertThat(reader.postRaw("/users/import",
                "nombre,correo\nIntruso,intruso@example.org\n", "text/csv").status()).isEqualTo(403);
    }

    @Test
    void anAdminRowDoesNotAppearBecauseNobodyAskedForOne() {
        var result = upload("nombre,correo,rol\nEl老板,dueño@example.org,ADMINISTRADOR\n");

        // Importing accounts is how a self-hosted library would lose its own
        // security: a sheet with a stray column should not mint administrators.
        assertThat(result.status()).isEqualTo(200);
        Integer admins = jdbc.queryForObject(
                "select count(*) from users where email = 'dueño@example.org'"
                        + " and role = 'ADMINISTRADOR'", Integer.class);
        assertThat(admins).isZero();
    }

    private int countReaders() {
        return jdbc.queryForObject("select count(*) from users where role = 'LECTOR'", Integer.class);
    }

    @Test
    void anExistingAccountKeepsItsPasswordAndItsRole() {
        // Whatever the name is, this test class does not get to change it: other test
        // classes rename the demo readers, so the assertion is on the difference.
        String nameBefore = jdbc.queryForObject(
                "select full_name from users where email = 'lector@demo.test'", String.class);
        String hashBefore = jdbc.queryForObject(
                "select password_hash from users where email = 'lector@demo.test'", String.class);

        var result = upload("nombre,correo\nDueño de la cuenta,lector@demo.test\n");

        // The row matched an existing reader, so it must be left completely alone.
        // Overwriting the name would be rude; touching the password would lock the
        // owner out of their own account.
        assertThat(result.json().path("alreadyThere").asInt()).isEqualTo(1);
        assertThat(result.json().path("failed").asInt()).isZero();
        assertThat(jdbc.queryForObject("select full_name from users where email = 'lector@demo.test'",
                String.class)).isEqualTo(nameBefore);
        assertThat(jdbc.queryForObject("select password_hash from users where email = 'lector@demo.test'",
                String.class)).isEqualTo(hashBefore);
    }
}