package com.openlibrary.catalog;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CatalogService {

    private static final int MAX_PAGE_SIZE = 100;

    /** Entity property names only: Hibernate renders the case-insensitive form. */
    private static final Map<String, String> SORTABLE = Map.of(
            "title", "title",
            "publicationyear", "publicationYear",
            "createdat", "createdAt",
            "pages", "pages");

    /** Only text columns may be sorted case-insensitively; lower() on a number is invalid SQL. */
    private static final Set<String> TEXT_COLUMNS = Set.of("title");

    private final BookRepository books;
    private final AuthorRepository authors;
    private final PublisherRepository publishers;
    private final CategoryRepository categories;
    private final AuditService audit;

    public CatalogService(BookRepository books, AuthorRepository authors,
                          PublisherRepository publishers, CategoryRepository categories,
                          AuditService audit) {
        this.books = books;
        this.authors = authors;
        this.publishers = publishers;
        this.categories = categories;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<CatalogDtos.BookSummary> search(CatalogQuery query) {
        Pageable pageable = PageRequest.of(
                Math.max(0, query.page()),
                Math.min(Math.max(1, query.size()), MAX_PAGE_SIZE),
                sortOf(query.sort()));

        Page<Book> found = books.search(
                blankToNull(query.q()),
                "%" + Book.fold(query.q()) + "%",
                blankToNull(query.q()),
                query.categoryId(),
                query.publisherId(),
                query.year(),
                query.language() == null ? null : query.language().toLowerCase(Locale.ROOT),
                pageable);

        return found.map(CatalogService::toSummary);
    }

    @Transactional(readOnly = true)
    public CatalogDtos.BookDetail detail(Long id) {
        return toDetail(find(id));
    }

    @Transactional
    public CatalogDtos.BookDetail create(CatalogDtos.UpsertBookRequest body, Long actorId) {
        var book = new Book(body.title().trim());
        applyIsbn(book, body.isbn(), null);
        applyDetails(book, body);
        books.save(book);

        audit.record(actorId, "book.created", "book", book.getId(),
                Map.of("title", book.getTitle()));

        return toDetail(book);
    }

    @Transactional
    public CatalogDtos.BookDetail update(Long id, CatalogDtos.UpsertBookRequest body, Long actorId) {
        Book book = find(id);
        applyIsbn(book, body.isbn(), id);
        applyDetails(book, body);
        books.save(book);

        audit.record(actorId, "book.updated", "book", id, Map.of("title", book.getTitle()));

        return toDetail(book);
    }

    @Transactional
    public void delete(Long id, Long actorId) {
        Book book = find(id);
        books.delete(book);
        audit.record(actorId, "book.deleted", "book", id, Map.of("title", book.getTitle()));
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.AuthorSummary> authors(String q) {
        var found = q == null || q.isBlank()
                ? authors.findAll()
                : authors.findByNameContainingIgnoreCaseOrderBySortNameAsc(q.trim());
        return found.stream()
                .map(a -> new CatalogDtos.AuthorSummary(a.getId(), a.getName(), a.getSortName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.PublisherSummary> publishersInUse() {
        return books.usedPublishers().stream()
                .map(p -> new CatalogDtos.PublisherSummary(p.getId(), p.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.CategoryRef> categories() {
        return categories.findAll().stream()
                .map(c -> new CatalogDtos.CategoryRef(c.getId(), c.getName(), c.getSlug()))
                .toList();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void applyIsbn(Book book, String raw, Long selfId) {
        book.setIsbn(raw);
        if (book.getIsbn13() == null) {
            return;
        }
        books.findByIsbn13(book.getIsbn13())
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "isbn_taken",
                            "Ese ISBN ya lo tiene otro libro.",
                            List.of(new ApiException.FieldError("isbn", "ISBN ya registrado")));
                });
    }

    private void applyDetails(Book book, CatalogDtos.UpsertBookRequest body) {
        book.setDetails(
                body.title().trim(),
                trimToNull(body.subtitle()),
                body.publicationYear(),
                trimToNull(body.language()),
                body.pages(),
                trimToNull(body.summary()),
                trimToNull(body.coverUrl()),
                trimToNull(body.edition()),
                resolvePublisher(body.publisher()));

        book.setAuthors(resolveAuthors(body.authors()));
        book.setCategories(resolveCategories(body.categories()));
        // Authors are part of the search text, so reindex once everything is set.
        book.reindex();
    }

    private List<Author> resolveAuthors(List<CatalogDtos.AuthorInput> inputs) {
        var ordered = new java.util.ArrayList<Author>();
        var seen = new LinkedHashSet<String>();
        for (var input : inputs) {
            String name = input.name().trim();
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            ordered.add(authors.findByNameIgnoreCase(name)
                    .orElseGet(() -> authors.save(new Author(name))));
        }
        if (ordered.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no_authors",
                    "Un libro necesita al menos un autor.",
                    List.of(new ApiException.FieldError("authors", "Indica al menos un autor")));
        }
        return ordered;
    }

    private Set<Category> resolveCategories(List<String> names) {
        var found = new LinkedHashSet<Category>();
        if (names == null) {
            return found;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            found.add(categories.findByNameIgnoreCase(trimmed)
                    .orElseGet(() -> categories.save(new Category(trimmed))));
        }
        return found;
    }

    private Publisher resolvePublisher(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return publishers.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> publishers.save(new Publisher(trimmed)));
    }

    private Book find(Long id) {
        return books.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "book_not_found",
                        "No existe el libro " + id + "."));
    }

    /**
     * Parses "field:direction" pairs separated by commas, e.g. "title:asc,publicationYear:desc".
     * Only {@link #SORTABLE} fields reach ORDER BY; anything else is ignored, so the
     * query string can never be used to inject SQL.
     */
    private static Sort sortOf(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.asc("title").ignoreCase());
        }
        var orders = new java.util.ArrayList<Sort.Order>();
        for (String token : sort.split(",")) {
            var bits = token.split(":");
            String property = SORTABLE.get(bits[0].trim().toLowerCase(Locale.ROOT));
            if (property == null) {
                continue;
            }
            boolean descending = bits.length > 1 && "desc".equalsIgnoreCase(bits[1].trim());
            Sort.Order order = descending
                    ? Sort.Order.desc(property)
                    : Sort.Order.asc(property);
            orders.add(TEXT_COLUMNS.contains(property) ? order.ignoreCase() : order);
        }
        return orders.isEmpty()
                ? Sort.by(Sort.Order.asc("title").ignoreCase())
                : Sort.by(orders);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CatalogDtos.BookSummary toSummary(Book book) {
        return new CatalogDtos.BookSummary(
                book.getId(), book.getTitle(), book.getSubtitle(),
                authorRefs(book),
                book.getPublisher() == null ? null : book.getPublisher().getName(),
                book.getPublicationYear(), book.getLanguage(), book.getPages(),
                book.getCoverUrl(), book.getIsbn13());
    }

    private static CatalogDtos.BookDetail toDetail(Book book) {
        var categories = book.getCategories().stream()
                .map(link -> new CatalogDtos.CategoryRef(
                        link.getCategory().getId(), link.getCategory().getName(),
                        link.getCategory().getSlug()))
                .toList();

        return new CatalogDtos.BookDetail(
                book.getId(), book.getTitle(), book.getSubtitle(),
                authorRefs(book), categories,
                book.getPublisher() == null ? null : book.getPublisher().getName(),
                book.getPublicationYear(), book.getLanguage(), book.getPages(),
                book.getSummary() == null ? "" : book.getSummary(),
                book.getCoverUrl(), book.getEdition(), book.getIsbn13(), book.getIsbn10());
    }

    private static List<CatalogDtos.AuthorRef> authorRefs(Book book) {
        return book.getAuthors().stream()
                .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
                .map(link -> new CatalogDtos.AuthorRef(
                        link.getAuthor().getId(), link.getAuthor().getName(),
                        link.getRole(), link.getPosition()))
                .toList();
    }

    /** Read model for the search endpoint. */
    public record CatalogQuery(String q, Long categoryId, Long publisherId, Integer year,
                               String language, int page, int size, String sort) {
    }
}
