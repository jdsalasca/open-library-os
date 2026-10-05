package com.openlibrary.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The barcode a librarian scans. An EAN-13 is built from an internal-use prefix
 * plus a sequence number, so every copy has a printable code that no real product
 * can collide with.
 */
class CopyCodeTest {

    @Test
    void buildsAValidEan13FromTheSequenceNumber() {
        String barcode = CopyCode.barcode(1);

        assertThat(barcode).hasSize(13).startsWith("20000000000");
        assertThat(CopyCode.hasValidEan13Checksum(barcode))
                .as("checksum for %s", barcode).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 9, 10, 99, 100, 12345, 999999999})
    void everyGeneratedBarcodePassesItsChecksum(long sequence) {
        assertThat(CopyCode.hasValidEan13Checksum(CopyCode.barcode(sequence))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "9780306406157, true",
            "9780306406158, false",
            "0000000000000, true",
            "2000000000001, false",
    })
    void validatesRealIsbnsToo(String isbn, boolean valid) {
        assertThat(CopyCode.hasValidEan13Checksum(isbn)).isEqualTo(valid);
    }

    @Test
    void rejectsAChecksumFailureWithAClearMessage() {
        assertThatThrownBy(() -> CopyCode.requireValid("9780306406158"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9780306406158");
    }

    @Test
    void theQrPayloadIsTheCopyCodeNotTheIsbn() {
        assertThat(CopyCode.qrPayload(7)).isEqualTo(CopyCode.code(7));
    }

    @Test
    void theHumanCodeIsReadableAndUniquePerSequence() {
        assertThat(CopyCode.code(1)).isEqualTo(CopyCode.code(1));
        assertThat(CopyCode.code(1)).isNotEqualTo(CopyCode.code(2));
        assertThat(CopyCode.code(1)).startsWith("OL-");
    }

    @Test
    void theHumanCodeKeepsItsLeadingZeros() {
        assertThat(CopyCode.code(42)).isEqualTo("OL-0000000042");
    }

    @Test
    void aSequenceNumberOutOfRangeIsRejected() {
        assertThatThrownBy(() -> CopyCode.barcode(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CopyCode.barcode(10_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}