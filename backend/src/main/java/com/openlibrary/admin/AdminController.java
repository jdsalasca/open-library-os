package com.openlibrary.admin;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openlibrary.shared.ApiException;

/**
 * The whole library in one document, and back again.
 *
 * <p>Authorisation lives in {@code SecurityConfig}: moving a library between
 * machines is an administrator's job and nobody else's.
 */
@RestController
public class AdminController {

    private final LibraryTransferService transfer;
    private final ObjectMapper json;

    public AdminController(LibraryTransferService transfer, ObjectMapper json) {
        this.transfer = transfer;
        this.json = json;
    }

    @GetMapping(value = "/admin/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public LibraryDocument export() {
        return transfer.export();
    }

    /**
     * Imports a document as an upsert. The body arrives as a tree on purpose: the
     * document is versioned and mostly untyped rows, and this way a file that is
     * not an export at all gets a clear {@code not_an_export} instead of a
     * validation error about a field nobody knows.
     */
    @PostMapping(value = "/admin/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importLibrary(@RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.has("schemaVersion")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_an_export",
                    "El fichero no parece una exportacion de la biblioteca.");
        }
        int version = body.path("schemaVersion").asInt();
        if (version > LibraryDocument.VERSION) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_schema_version",
                    "Esta exportacion es de una version mas moderna (" + version + ")."
                            + " Actualiza el programa antes de importarla.");
        }
        LibraryDocument document;
        try {
            document = json.treeToValue(body, LibraryDocument.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not_an_export",
                    "El fichero esta Danado: " + e.getMessage());
        }
        var report = transfer.importDocument(document);
        return Map.of("created", report.created(), "updated", report.updated(),
                "skipped", report.skipped());
    }
}
