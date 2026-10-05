package com.openlibrary.catalog;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What to show on the first screen: how big the catalogue is, what arrived
 * lately, and anything matching what is being typed.
 *
 * <p>The catalogue already knows how to search, so this reuses it instead of
 * inventing a second search that would drift from the first one.
 */
@Service
public class SuggestionService {

    private static final int MAX = 6;

    private final BookRepository books;

    public SuggestionService(BookRepository books) {
        this.books = books;
    }

    @Transactional(readOnly = true)
    public CatalogDtos.Suggestions suggestions(String query) {
        List<CatalogDtos.BookSummary> recent = books
                .findAll(PageRequest.of(0, MAX, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(CatalogService::toSummary)
                .toList();

        if (query == null || query.isBlank()) {
            return new CatalogDtos.Suggestions(books.count(), recent, List.of());
        }

        String needle = Book.fold(query);
        var found = books.search(needle, "%" + needle + "%", "%" + needle + "%",
                null, null, null, null, PageRequest.of(0, MAX));
        return new CatalogDtos.Suggestions(
                books.count(),
                recent,
                found.getContent().stream().map(CatalogService::toSummary).toList());
    }

}