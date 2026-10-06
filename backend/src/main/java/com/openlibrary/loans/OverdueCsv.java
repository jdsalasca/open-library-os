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

    private OverdueCsv() {
    }

    /**
     * One row of either report.
     *
     * <p>Both spreadsheets are written by this class because the hard part is not
     * the columns, it is the quoting: a title with a comma, an accented name and a
     * cell that Excel would happily run as a formula.
     */
    record Row(String reader, String email, String title, String code, String dueAt,
               int daysLate, int position) {

        static Row overdue(String reader, String email, String title, String code,
                String dueAt, int daysLate) {
            return new Row(reader, email, title, code, dueAt, daysLate, 0);
        }

        static Row queue(String reader, String email, String title, int position, int daysWaiting) {
            return new Row(reader, email, title, "", "", daysWaiting, position);
        }
    }

    static String render(List<Row> rows) {
        return render(rows, "lector,correo,libro,ejemplar,vencido,dias_de_retraso", OverdueCsv::overdueCells);
    }

    /** The queue: who is next, for which book, and how long they have waited. */
    static String renderQueue(List<Row> rows) {
        return render(rows, "libro,lector,correo,puesto,esperando_dias", OverdueCsv::queueCells);
    }

    private static String render(List<Row> rows, String header,
            CellList cells) {
        StringBuilder out = new StringBuilder(BOM).append(header).append("\r\n");
        for (Row row : rows) {
            out.append(String.join(",", cells.of(row).stream().map(OverdueCsv::cell).toList()))
                    .append("\r\n");
        }
        return out.toString();
    }

    /** A row's cells, in order, already as text. */
    interface CellList {
        List<String> of(Row row);
    }

    private static List<String> overdueCells(Row row) {
        return List.of(row.reader(), row.email(), row.title(), row.code(), row.dueAt(),
                Integer.toString(row.daysLate()));
    }

    private static List<String> queueCells(Row row) {
        return List.of(row.title(), row.reader(), row.email(),
                Integer.toString(row.position()), Integer.toString(row.daysLate()));
    }

    /** Quotes only when needed, doubles the inner quotes, blocks formulas. */
    static String cell(String value) {
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
}