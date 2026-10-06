package com.openlibrary.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.openlibrary.inventory.InventoryDtos;
import com.openlibrary.inventory.InventoryService;
import com.openlibrary.isbn.Isbn;

/**
 * The first day of a self-hosted library is not an empty catalogue: it is three
 * hundred titles somebody already owns, in a spreadsheet. Restoring a full backup
 * does not help with that.
 *
 * <p>There is no new domain logic here on purpose. Every row becomes the same
 * {@code UpsertBookRequest} the manual form sends and is handed to the same service,
 * so the import cannot drift from the form: an ISBN the form would reject is an ISBN
 * the import rejects, with the same message.
 *
 * <p>Deliberately not {@code @Transactional}. Each row goes through the catalogue and
 * inventory services, which own their own transactions, so a rejected ISBN cannot
 * roll back the two hundred rows that came before it. The point of the report is that
 * the librarian can fix line 47 and upload again instead of starting over.
 */
@Service
public class CatalogueCsvImport {

    /** Column names as a Spanish or English spreadsheet would spell them. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        alias("titulo", "title");
        alias("autor", "author", "escritor");
        alias("isbn13", "isbn");
        alias("editorial", "publisher");
        alias("anio", "year", "publicacion");
        alias("idioma", "language", "lengua");
        alias("categoria", "category", "categorias");
        alias("copias", "copies", "ejemplares");
    }

    private final CatalogService catalog;
    private final InventoryService inventory;

    public CatalogueCsvImport(CatalogService catalog, InventoryService inventory) {
        this.catalog = catalog;
        this.inventory = inventory;
    }

    private static void alias(String canonical, String... others) {
        ALIASES.put(canonical, canonical);
        for (String other : others) {
            ALIASES.put(other, canonical);
        }
    }

    /**
     * One row's verdict, with its line number. "Three rows failed" is useless to
     * somebody holding the spreadsheet open in another window.
     */
    public record RowProblem(int line, String reason) {}

/**
 * The failed count is a real component, not a derived getter: Jackson serialises
 * record components and nothing else, so a {@code failed()} method produced a report
 * whose whole point was missing. One factory keeps the two from disagreeing.
 */
public record Report(int created, int updated, int copiesAdded, int failed,
        List<RowProblem> problems) {

    static Report of(int created, int updated, int copies, List<RowProblem> problems) {
        List<RowProblem> copy = List.copyOf(problems);
        return new Report(created, updated, copies, copy.size(), copy);
    }
}

    public Report importCsv(String text, Long actorId) {
        List<String> lines = stripBom(text).lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return Report.of(0, 0, 0, List.of());
        }

        char delimiter = detectDelimiter(lines.get(0));
        Map<String, Integer> columns = header(Csv.split(lines.get(0), delimiter));
        if (!columns.containsKey("titulo")) {
            // Nothing can be done with a file we cannot read, and reporting zero rows
            // imported would look like success.
            throw new com.openlibrary.shared.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "csv_unreadable",
                    "El fichero necesita una columna de titulo. Se esperaban titulo, autor,"
                            + " isbn13, editorial, anio, idioma, categoria y copias.");
        }

        int created = 0;
        int updated = 0;
        int copiesAdded = 0;
        var problems = new ArrayList<RowProblem>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            try {
                Map<String, String> row = columnsOf(columns, Csv.split(lines.get(i), delimiter));
                String isbn = isbnOf(row.get("isbn13"));

                var request = new CatalogDtos.UpsertBookRequest(
                        required(row, "titulo"), row.get("subtitulo"), isbn,
                        row.get("editorial"), yearOf(row.get("anio")), row.get("idioma"),
                        integerOf(row.get("paginas")), null, null, null,
                        authorsOf(row.get("autor")), splitNames(row.get("categoria")));

                var existing = isbn == null ? null : catalog.findByIsbn(isbn);
                Long bookId;
                if (existing == null) {
                    bookId = catalog.create(request, actorId).id();
                    created++;
                } else {
                    bookId = catalog.update(existing.id(), request, actorId).id();
                    updated++;
                }

                copiesAdded += topUpCopies(bookId, integerOf(row.get("copias")));
            } catch (RuntimeException e) {
                problems.add(new RowProblem(lineNumber, reasonOf(e)));
            }
        }

        return Report.of(created, updated, copiesAdded, problems);
    }

    /**
 * The spreadsheet column says how many copies the library owns, not how many to add.
 *
 * <p>Otherwise uploading the same file twice — or fixing line 47 and uploading again —
 * would quietly print another label for every copy, and the desk would find out weeks
 * later from a stock count. Adding only the difference keeps the import safe to repeat,
 * and still tops the shelf up when the number goes up.
 */
private int topUpCopies(Long bookId, Integer wanted) {
        if (wanted == null || wanted <= 0) {
            return 0;
        }
        long alreadyThere = inventory.copiesOf(bookId);
        int missing = (int) Math.max(0, wanted - alreadyThere);
        if (missing == 0) {
            return 0;
        }
        inventory.createCopies(new InventoryDtos.CreateCopiesRequest(bookId, missing, null, null, null));
        return missing;
    }

    private static String reasonOf(RuntimeException e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String required(Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sin " + name);
        }
        return value.trim();
    }

    private static Map<String, String> columnsOf(Map<String, Integer> columns, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        columns.forEach((name, index) -> {
            if (index < cells.size()) {
                String value = cells.get(index).trim();
                if (!value.isEmpty()) {
                    row.put(name, value);
                }
            }
        });
        return row;
    }

    private static Map<String, Integer> header(List<String> cells) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = canonical(cells.get(i));
            if (!name.isEmpty()) {
                // First wins: a duplicated column is a typo, not an override.
                columns.putIfAbsent(name, i);
            }
        }
        return columns;
    }

    private static String canonical(String raw) {
        String folded = Book.fold(raw == null ? "" : raw).replaceAll("[^a-z0-9]", "");
        return ALIASES.getOrDefault(folded, folded);
    }

    /** Spanish sheets are semicolon-separated, English ones comma. The header tells us. */
    private static char detectDelimiter(String headerLine) {
        return count(headerLine, ';') > count(headerLine, ',') ? ';' : ',';
    }

    private static int count(String line, char target) {
        int found = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == target) {
                found++;
            }
        }
        return found;
    }

    /**
     * {@link Isbn#parse} answers null when it cannot read the number, so the message
     * that reaches the librarian is written here: a row must not fail with a
     * NullPointerException and a line number.
     */
    private static String isbnOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Isbn parsed = Isbn.parse(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("ISBN invalido: " + raw);
        }
        return parsed.normalised();
    }

    private static Integer yearOf(String raw) {
        Integer year = integerOf(raw);
        if (year != null && (year < 1450 || year > 2100)) {
            throw new IllegalArgumentException("anio fuera de rango: " + raw);
        }
        return year;
    }

    private static Integer integerOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("no es un numero: " + raw);
        }
    }

    /**
     * Collaborators are separated with {@code ;} or {@code |}, never a comma: the
     * comma is the column delimiter. This is the one guess worth making, because
     * "Cortazar, Julio" as an author is a sheet where somebody typed a name in one
     * column, and silently splitting it would be worse than not parsing it.
     */
    private static List<CatalogDtos.AuthorInput> authorsOf(String raw) {
        List<CatalogDtos.AuthorInput> inputs = new ArrayList<>();
        for (String name : splitNames(raw)) {
            inputs.add(new CatalogDtos.AuthorInput(name, null));
        }
        return List.copyOf(inputs);
    }

    private static List<String> splitNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String part : raw.split("[;|]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Splitting a CSV line: the quote marks and the doubled quotes inside them. */
    static final class Csv {

        private Csv() {}

        static List<String> split(String line, char delimiter) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c != '"') {
                        cell.append(c);
                    } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == delimiter) {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cell.toString());
            return cells;
        }
    }
}