package com.openlibrary.catalog;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Authorisation for these paths lives in {@code SecurityConfig}. */
@RestController
@RequestMapping("/catalog")
public class CatalogController {

    private final CatalogueCsvImport csvImport;
    private final CatalogService catalog;
    private final CurrentUser caller;
    private final SuggestionService suggestions;

    public CatalogController(CatalogService catalog, CurrentUser caller,
            SuggestionService suggestions, CatalogueCsvImport csvImport) {
        this.csvImport = csvImport;
        this.catalog = catalog;
        this.caller = caller;
        this.suggestions = suggestions;
    }

    /**
     * The shelf count, the newest arrivals and a quick search. Any signed-in
     * person may browse the catalogue, so this is not staff-only.
     */
    @GetMapping("/suggestions")
    public CatalogDtos.Suggestions suggestions(@RequestParam(required = false) String q) {
        return suggestions.suggestions(q);
    }

    @GetMapping("/books")
    public CatalogDtos.PageResponse<CatalogDtos.BookSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Long publisher,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        return CatalogDtos.PageResponse.of(catalog.search(new CatalogService.CatalogQuery(
                q, category, publisher, year, language, page, size, sort)));
    }

    @GetMapping("/books/{id}")
    public CatalogDtos.BookDetail detail(@PathVariable Long id) {
        return catalog.detail(id);
    }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDtos.BookDetail create(@Valid @RequestBody CatalogDtos.UpsertBookRequest body) {
        return catalog.create(body, caller.id());
    }

    @PutMapping("/books/{id}")
    public CatalogDtos.BookDetail update(@PathVariable Long id,
                                         @Valid @RequestBody CatalogDtos.UpsertBookRequest body) {
        return catalog.update(id, body, caller.id());
    }

    @DeleteMapping("/books/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        catalog.delete(id, caller.id());
    }

    /**
     * The spreadsheet a real library arrives with. Body is the file as text, so the
     * browser can hand it over with FileReader and no multipart parser.
     */
    @PostMapping("/import")
    public CatalogueCsvImport.Report importCatalogue(
            @RequestHeader("Content-Type") String contentType,
            @RequestBody String csv) {
        if (!contentType.toLowerCase(Locale.ROOT).contains("csv")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "csv_expected",
                    "El fichero tiene que ser un CSV.");
        }
        return csvImport.importCsv(csv, caller.id());
    }
    @GetMapping("/authors")
    public List<CatalogDtos.AuthorSummary> authors(@RequestParam(required = false) String q) {
        return catalog.authors(q);
    }

    @GetMapping("/publishers")
    public List<CatalogDtos.PublisherSummary> publishers() {
        return catalog.publishersInUse();
    }

    @GetMapping("/categories")
    public List<CatalogDtos.CategoryRef> categories() {
        return catalog.categories();
    }
}
