package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ISBN checksums and normalisation, the contract every provider has to honour. */
class IsbnTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "9780306406157", // valid ISBN-13
            "9788491058106",
            "0-306-40615-2", // valid ISBN-10 with hyphens
            "0306406152",    // valid ISBN-10, bare
            " 9780306406157 ", // surrounding whitespace
    })
    void acceptsWellFormedIsbn(String raw) {
        assertThat(Isbn.parse(raw)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9780306406158", // last digit wrong
            "0306406153",    // ISBN-10 checksum wrong
            "1402894626",    // real-looking number, but its ISBN-10 checksum fails
            "97803064061",   // too short
            "abcdefghijkl",  // not numeric
            "",              // empty
            "97803064061570", // too long
    })
    void rejectsInvalidIsbn(String raw) {
        assertThat(Isbn.parse(raw)).isNull();
    }

    @Test
    void normalisesToThirteenDigitsWithoutHyphens() {
        Isbn isbn = Isbn.parse("0-306-40615-2");
        assertThat(isbn).isNotNull();
        assertThat(isbn.normalised()).isEqualTo("9780306406157");
    }

    @Test
    void reportsWhetherItWasSuppliedAsTenOrThirteen() {
        assertThat(Isbn.parse("0306406152").isTen()).isTrue();
        assertThat(Isbn.parse("9780306406157").isTen()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            // Same book, two lengths: the 10-digit form widens to the 13-digit one.
            "0306406152, 9780306406157",
    })
    void derivesTheIsbn13FromAnIsbn10(String ten, String expectedThirteen) {
        Isbn isbn = Isbn.parse(ten);
        assertThat(isbn).isNotNull();
        assertThat(isbn.normalised()).isEqualTo(expectedThirteen);
    }

    @Test
    void throwsWhenAskedForTheNumberOfAnInvalidIsbn() {
        assertThatThrownBy(() -> Isbn.require("not-an-isbn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-an-isbn");
    }
}
