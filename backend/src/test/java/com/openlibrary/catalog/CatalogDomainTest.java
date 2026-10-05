package com.openlibrary.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure catalogue rules: folding for search, slugs for categories, ISBN handling. */
class CatalogDomainTest {

    @ParameterizedTest
    @CsvSource({
            "Marquez,        marquez",
            "GARCÍA MÁRQUEZ, garcia marquez",
            "  Juan  Luis ,  juan luis",
            "Borges,         borges",
    })
    void foldsNamesSoSearchIgnoresCaseAndAccents(String raw, String expected) {
        assertThat(Book.fold(raw)).isEqualTo(expected);
    }

    @Test
    void sortNameIsJustTheFoldedName() {
        assertThat(Book.sortNameOf("Gabriel García Márquez")).isEqualTo("gabriel garcia marquez");
    }

    @ParameterizedTest
    @CsvSource({
            "Historia,           historia",
            "Ciencia Ficción,    ciencia-ficcion",
            "  Novela negra ,    novela-negra",
            "Infantil y juvenil, infantil-y-juvenil",
    })
    void buildsStableSlugsForCategories(String raw, String expected) {
        assertThat(Category.slugOf(raw)).isEqualTo(expected);
    }

    @Test
    void twoSpellingsOfTheSameCategoryShareASlug() {
        assertThat(Category.slugOf("Ciencia Ficción")).isEqualTo(Category.slugOf("ciencia ficcion"));
    }

    @Test
    void anIsbnSurvivesHyphensAndSpaces() {
        var book = new Book("Dune");
        book.setIsbn(" 978-0-306-40615-7 ");

        assertThat(book.getIsbn13()).isEqualTo("9780306406157");
        assertThat(book.getIsbn10()).as("the 13-digit form has no 10-digit twin").isNull();
    }

    @Test
    void anIsbn10IsStoredBothWays() {
        var book = new Book("Dune");
        book.setIsbn("0306406152");

        assertThat(book.getIsbn13()).isEqualTo("9780306406157");
        assertThat(book.getIsbn10()).isEqualTo("0306406152");
    }

    @Test
    void aBlankIsbnMeansNoIsbnAtAll() {
        var book = new Book("Dune");
        book.setIsbn("   ");

        assertThat(book.getIsbn13()).isNull();
        assertThat(book.getIsbn10()).isNull();
    }

    @Test
    void anInvalidIsbnIsRejectedWithTheFieldItCameFrom() {
        var book = new Book("Dune");

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.openlibrary.shared.ApiException.class, () -> book.setIsbn("123"));

        assertThat(error.code()).isEqualTo("invalid_isbn");
        assertThat(error.fieldErrors()).singleElement()
                .satisfies(field -> assertThat(field.field()).isEqualTo("isbn"));
    }

    @Test
    void theSearchTextCoversTitleSubtitleIsbnAndAuthors() {
        var book = new Book("Cien años de soledad");
        book.setIsbn("9780306406157");
        book.setAuthors(java.util.List.of(new Author("Gabriel García Márquez")));
        book.reindex();

        assertThat(book.getSearchText())
                .contains("cien anos de soledad")
                .contains("9780306406157")
                .contains("gabriel garcia marquez")
                .doesNotContain("ñ");
    }
}