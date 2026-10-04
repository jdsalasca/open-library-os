package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape every provider normalises into. Two upstream APIs with different
 * vocabularies must collapse into this, so the catalogue and the UI only ever
 * deal with one model.
 */
class ExternalBookTest {

    @Test
    void dropsFieldsTheProviderLeftEmpty() {
        var book = new ExternalBook.Mutable(
                "9780306406157",
                "Neuromancer",
                null,
                List.of("William Gibson"),
                "Ace",
                1984,
                List.of("Ficción científica"),
                null,
                271,
                "En un futuro…",
                "https://example.test/c.jpg");

        var complete = book.toRecord();

        assertThat(complete.title()).isEqualTo("Neuromancer");
        assertThat(complete.subtitle()).isNull();
        assertThat(complete.coverUrl()).isEqualTo("https://example.test/c.jpg");
    }

    @Test
    void trimsAndDropsBlankStringsSoTheCatalogueStaysClean() {
        var book = new ExternalBook.Mutable(
                "9780306406157",
                "  Neuromancer  ",
                "   ",
                List.of(" William Gibson ", ""),
                "  ",
                1984,
                List.of("Ficción", "  "),
                "en",
                271,
                "  ",
                null);

        var complete = book.toRecord();

        assertThat(complete.title()).isEqualTo("Neuromancer");
        assertThat(complete.subtitle()).isNull();
        assertThat(complete.publisher()).isNull();
        assertThat(complete.authors()).containsExactly("William Gibson");
        assertThat(complete.categories()).containsExactly("Ficción");
        assertThat(complete.summary()).isNull();
        assertThat(complete.coverUrl()).isNull();
    }

    @Test
    void keepsZeroPagesBecauseZeroIsDataAndNullIsNot() {
        var book = new ExternalBook.Mutable("9780306406157", "X", null,
                List.of(), null, null, List.of(), null, 0, null, null);

        assertThat(book.toRecord().pages()).isZero();
    }

    @Test
    void exposesTheSourceAndTheIsbnItWasFetchedFor() {
        var record = new ExternalBook.Mutable(
                "9780306406157", "Neuromancer", null, List.of("William Gibson"),
                "Ace", 1984, List.of(), "en", 271, null, null)
                .toRecord();

        assertThat(record.source()).isEqualTo("openlibrary");
        assertThat(record.isbn()).isEqualTo("9780306406157");
    }

    @Test
    void acceptsTheSourceOfAnyProvider() {
        var record = new ExternalBook.Mutable(
                "googlebooks", "9780306406157", "Neuromancer", null,
                List.of("William Gibson"), "Ace", 1984, List.of(), "en", 271, null, null)
                .toRecord();

        assertThat(record.source()).isEqualTo("googlebooks");
        // Cleanup still applies when the source is explicit.
        assertThat(record.title()).isEqualTo("Neuromancer");
        assertThat(record.authors()).containsExactly("William Gibson");
    }
}
