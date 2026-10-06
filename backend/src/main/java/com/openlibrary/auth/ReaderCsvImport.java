package com.openlibrary.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;

/**
 * A library that moves to self-hosting arrives with its members already on a
 * spreadsheet, and the only way in today is one account at a time.
 *
 * <p>Two rules, both about not trusting the file:
 *
 * <ul>
 * <li>The sheet has no passwords, and this class will not invent one the librarian
 * has to distribute. Every account arrives with a random password and
 * {@code mustChangePassword}, which the PasswordGate already enforces: nobody can
 * reach the API with it, and the owner picks their own on first login. A sheet full
 * of accounts with a shared password would be a security incident with a button.
 * <li>Every imported account is a LECTOR. Reading a role out of the file would mean
 * a stray column could mint an administrator, and this is precisely the tool someone
 * would use to do that by accident. Staff accounts are created by staff, one at a
 * time, where the choice is deliberate.
 * </ul>
 *
 * <p>Not {@code @Transactional}, on purpose. Each row goes through
 * {@link UserRepository#save} inside its own transaction, so a malformed address on
 * line 47 cannot undo the two hundred above it. The report is what makes that
 * workable: fix the line, upload again.
 */
@Service
public class ReaderCsvImport {

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("nombre", "nombre");
        ALIASES.put("name", "nombre");
        ALIASES.put("nombrecompleto", "nombre");
        ALIASES.put("correo", "correo");
        ALIASES.put("email", "correo");
        ALIASES.put("correoelectronico", "correo");
    }

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserRepository users;
    private final AuditService audit;
    private final PasswordEncoder passwords;

    public ReaderCsvImport(UserRepository users, AuditService audit, PasswordEncoder passwords) {
        this.users = users;
        this.audit = audit;
        this.passwords = passwords;
    }

    public record RowProblem(int line, String reason) {}

    /**
     * {@code alreadyThere} is not a failure: re-uploading the roster after adding two
     * members should say "798 new, 2 already existed", not "2 errors".
     */
    public record Report(int created, int alreadyThere, int failed, List<RowProblem> problems) {}

    public Report importCsv(String text, Long actorId) {
        List<String> lines = stripBom(text).lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return new Report(0, 0, 0, List.of());
        }

        char delimiter = detectDelimiter(lines.get(0));
        Map<String, Integer> columns = header(Csv.split(lines.get(0), delimiter));
        if (!columns.containsKey("nombre") || !columns.containsKey("correo")) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "csv_unreadable",
                    "El fichero necesita columnas de nombre y correo.");
        }

        int created = 0;
        int alreadyThere = 0;
        var problems = new ArrayList<RowProblem>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            try {
                Map<String, String> row = columnsOf(columns, Csv.split(lines.get(i), delimiter));
                // A blank cell is simply absent from the row, so this has to be
                // checked before the value is read, not after.
                String email = row.getOrDefault("correo", "").trim()
                        .toLowerCase(java.util.Locale.ROOT);
                if (email.isEmpty()) {
                    throw new IllegalArgumentException("falta el correo");
                }
                if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    throw new IllegalArgumentException("correo invalido: " + row.get("correo"));
                }

                if (users.existsByEmailIgnoreCase(email)) {
                    alreadyThere++;
                    continue;
                }

                String name = row.getOrDefault("nombre", "").trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("falta el nombre");
                }
                User saved = users.save(new User(email, passwords.encode(randomPassword()),
                        name, Role.LECTOR, true));
                users.flush();
                audit.record(actorId, "user.imported", "user", saved.getId(),
                        Map.of("email", email, "role", Role.LECTOR.name()));
                created++;
            } catch (RuntimeException e) {
                problems.add(new RowProblem(lineNumber, reasonOf(e)));
            }
        }

        return new Report(created, alreadyThere, problems.size(), List.copyOf(problems));
    }

    /**
 * Row failure, translated.
 *
 * <p>An empty cell reaches Postgres as a NOT NULL violation whose message names a
 * column and a driver class. "Cannot invoke java.sql.SQLException..." is not
 * something a librarian can act on, so the cases we can recognise are said in words.
 * Anything unrecognised keeps its original text: hiding a failure we do not
 * understand would be worse than showing it.
 */
private static String reasonOf(RuntimeException e) {
        String raw = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
        if (raw.contains("Cannot invoke \"java.sql.SQLException\"")
                || raw.contains("violates not-null constraint")) {
            return "falta el correo";
        }
        if (raw.contains("duplicate key value")) {
            return "ese correo ya esta registrado";
        }
        return raw;
    }

    /**
     * Long enough that nobody can guess it, short enough that the owner can paste it
     * into the change-password form once. Excluded the letters and digits that look
     * alike, because somebody will read this off a printed sheet.
     */
    private static String randomPassword() {
        var random = new SecureRandom();
        var bytes = new byte[12];
        random.nextBytes(bytes);
        var raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (ALPHABET.indexOf(c) >= 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Map<String, String> columnsOf(Map<String, Integer> columns, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        columns.forEach((name, index) -> {
            if (index < cells.size() && !cells.get(index).isBlank()) {
                row.put(name, cells.get(index).trim());
            }
        });
        return row;
    }

    private static Map<String, Integer> header(List<String> cells) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = canonical(cells.get(i));
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, i);
            }
        }
        return columns;
    }

    private static String canonical(String raw) {
        String folded = com.openlibrary.catalog.Book.fold(raw == null ? "" : raw)
                .replaceAll("[^a-z0-9]", "");
        return ALIASES.getOrDefault(folded, folded);
    }

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