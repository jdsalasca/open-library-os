package com.openlibrary.system;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import com.openlibrary.shared.CurrentUser;

/**
 * The name of <em>this</em> library.
 *
 * <p>"Open Library OS" is the project's name. Somebody self-hosting it runs a
 * library with a name of its own, and the first screen they ever see is the
 * login form, so that name belongs there — and on the slip a reader hands over.
 *
 * <p>Reading it needs no session: the login screen has none yet. Renaming it is
 * for an administrator, and {@code settings:manage} already means "this person
 * decides how the library lends", which is the same kind of decision.
 */
@RestController
@RequestMapping("/system")
public class LibraryProfileController {

    private static final String KEY_NAME = "library.name";
    private static final int MAX = 120;

    private final JdbcTemplate jdbc;
    private final CurrentUser caller;
    private final AuditService audit;

    public LibraryProfileController(JdbcTemplate jdbc, CurrentUser caller, AuditService audit) {
        this.jdbc = jdbc;
        this.caller = caller;
        this.audit = audit;
    }

    @GetMapping("/library")
    public Map<String, String> library() {
        return Map.of("name", read());
    }

    @PutMapping("/library/name")
    public Map<String, String> rename(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty() || name.length() > MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_library_name",
                    "El nombre debe tener entre 1 y " + MAX + " caracteres.");
        }
        jdbc.update("update app_config set value = ?, updated_at = now() where key = ?", name, KEY_NAME);
        audit.record(caller.id(), "library.renamed", "app_config", null, Map.of("name", name));
        return Map.of("name", name);
    }

    /** Falls back to the shipped default rather than failing the login screen. */
    private String read() {
        var names = jdbc.query("select value from app_config where key = ?",
                (rs, n) -> rs.getString("value"), KEY_NAME);
        return names.isEmpty() || names.get(0).isBlank() ? "Biblioteca" : names.get(0).trim();
    }
}