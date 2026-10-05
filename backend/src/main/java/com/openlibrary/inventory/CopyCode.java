package com.openlibrary.inventory;

/**
 * Code generation for physical copies.
 *
 * <p>A barcode only has to be unique in your own library and survive a scanner, so
 * the numbers are an EAN-13 built from the GS1 internal-use prefix {@code 20} plus
 * a zero-padded sequence. That gives a standard, printable code that no retail
 * product can collide with.
 *
 * <p>Two forms are kept on every copy: the human-readable code a librarian types
 * or reads out ({@code OL-0000000042}) and the scanned one ({@code 2000000000424}).
 * Both are derived from the same sequence number, so neither can drift.
 */
public final class CopyCode {

    /** GS1 prefix reserved for internal use: never issued to a real product. */
    static final String INTERNAL_PREFIX = "20";
    static final int SEQUENCE_DIGITS = 10;
    static final long MAX_SEQUENCE = 9_999_999_999L;

    static final String HUMAN_PREFIX = "OL-";
    static final int HUMAN_DIGITS = 10;

    private CopyCode() {
    }

    /** The scanned EAN-13 for a sequence number. */
    public static String barcode(long sequence) {
        requireSequence(sequence);
        String body = INTERNAL_PREFIX + pad(sequence, SEQUENCE_DIGITS);
        return body + ean13CheckDigit(body);
    }

    /** The code a person reads out loud: same sequence, easier to remember. */
    public static String code(long sequence) {
        requireSequence(sequence);
        return HUMAN_PREFIX + pad(sequence, HUMAN_DIGITS);
    }

    /** What the QR encodes: the same code, so any scanner app can look it up. */
    public static String qrPayload(long sequence) {
        return code(sequence);
    }

    public static boolean hasValidEan13Checksum(String barcode) {
        if (barcode == null || barcode.length() != 13) {
            return false;
        }
        String body = barcode.substring(0, 12);
        if (!body.chars().allMatch(Character::isDigit)
                || !Character.isDigit(barcode.charAt(12))) {
            return false;
        }
        return ean13CheckDigit(body) == barcode.charAt(12) - '0';
    }

    /** Throws instead of returning false, for user input where silence is a bug. */
    public static String requireValid(String barcode) {
        if (!hasValidEan13Checksum(barcode)) {
            throw new IllegalArgumentException("Codigo de barras invalido: " + barcode);
        }
        return barcode;
    }

    private static void requireSequence(long sequence) {
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Numero de ejemplar fuera de rango: " + sequence);
        }
    }

    private static String pad(long value, int digits) {
        return String.format("%0" + digits + "d", value);
    }

    /** Weights alternate 1 and 3 from the left, over the 12 digits before the check. */
    static int ean13CheckDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = Character.digit(body.charAt(i), 10);
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }
}