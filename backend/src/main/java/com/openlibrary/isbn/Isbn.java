package com.openlibrary.isbn;

import java.util.Objects;

/**
 * An ISBN, always normalised to its 13-digit form.
 *
 * <p>Deliberately a plain value object with no dependencies: every provider and the
 * catalogue share these rules, and they are pure arithmetic, so they need no Spring
 * context and stay trivially testable.
 *
 * <p>ISBN-10 is converted by the standard 978 prefix plus a recomputed check digit.
 */
public final class Isbn {

    private static final int ISBN10_LENGTH = 10;
    private static final int ISBN13_LENGTH = 13;

    private final String digits;
    private final boolean ten;

    private Isbn(String digits, boolean ten) {
        this.digits = digits;
        this.ten = ten;
    }

    /** Returns null for anything that is not a valid ISBN; never throws. */
    public static Isbn parse(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\s-]", "");
        return switch (cleaned.length()) {
            case ISBN10_LENGTH -> isValidIsbn10(cleaned) ? toIsbn13(cleaned) : null;
            case ISBN13_LENGTH -> isValidIsbn13(cleaned) ? new Isbn(cleaned, false) : null;
            default -> null;
        };
    }

    /** Same as {@link #parse} but for user input, where silence would be a bug. */
    public static Isbn require(String raw) {
        Isbn isbn = parse(raw);
        if (isbn == null) {
            throw new IllegalArgumentException("ISBN invalido: " + raw);
        }
        return isbn;
    }

    public String normalised() {
        return digits;
    }

    /** True when the caller supplied the 10-digit form, before widening to 13. */
    public boolean isTen() {
        return ten;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Isbn isbn && digits.equals(isbn.digits);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(digits);
    }

    @Override
    public String toString() {
        return digits;
    }

    // ── Checksums ─────────────────────────────────────────────────────────────
    // Both schemes weight each position (10, 9, … 1) and require sum mod 11 == 0.

    private static boolean isValidIsbn10(String value) {
        if (!value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < ISBN10_LENGTH; i++) {
            sum += (ISBN10_LENGTH - i) * Character.digit(value.charAt(i), 10);
        }
        return sum % 11 == 0;
    }

    private static boolean isValidIsbn13(String value) {
        if (!value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < ISBN13_LENGTH; i++) {
            int digit = Character.digit(value.charAt(i), 10);
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return sum % 10 == 0;
    }

    /** Widens ISBN-10 to ISBN-13: prepend 978 and recompute the check digit. */
    private static Isbn toIsbn13(String ten) {
        StringBuilder body = new StringBuilder("978").append(ten, 0, ISBN10_LENGTH - 1);
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = Character.digit(body.charAt(i), 10);
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - sum % 10) % 10;
        return new Isbn(body.append(check).toString(), true);
    }
}
