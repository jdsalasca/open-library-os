package com.openlibrary.loans;

import java.math.BigDecimal;
import java.util.List;

/**
 * The overdue list as a spreadsheet.
 *
 * <p>Calling twenty people needs a list you can work down. Three details make the
 * difference between a file that helps and one that wastes an afternoon:
 *
 * <ul>
 *   <li>A **byte order mark**, or Excel reads "García" as "GarcÃ­a".
 *   <li>Real quoting, because a book title is entitled to contain a comma.
 *   <li>A leading apostrophe on cells starting with {@code = + - @}. Excel and
 *       LibreOffice run those as formulas, and a title is not the place to be
 *       running somebody else's spreadsheet.
 * </ul>
 */
final class OverdueCsv {

    private static final String BOM = "\uFEFF";
    private static final String HEADER =
            "lector,correo,libro,ejemplar,vencido,dias_de_retraso";

    private OverdueCsv() {
    }

    record Row(String reader, String email, String title, String code, String dueAt, int daysLate) {
    }

    static String render(List<Row> rows) {
        StringBuilder out = new StringBuilder(BOM).append(HEADER).append("\r\n");
        for (Row row : rows) {
            out.append(cell(row.reader())).append(',')
                    .append(cell(row.email())).append(',')
                    .append(cell(row.title())).append(',')
                    .append(cell(row.code())).append(',')
                    .append(cell(row.dueAt())).append(',')
                    .append(row.daysLate())
                    .append("\r\n");
        }
        return out.toString();
    }

    /** Quotes only when needed, doubles the inner quotes, blocks formulas. */
    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuotes = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    static BigDecimal daysLate(java.time.Instant dueAt, java.time.LocalDate today) {
        return BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(
                dueAt.atZone(java.time.ZoneOffset.UTC).toLocalDate(), today));
    }
}