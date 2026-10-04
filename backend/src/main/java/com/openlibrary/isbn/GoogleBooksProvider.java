package com.openlibrary.isbn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fills a book from Google Books.
 *
 * <p>Second implementation of the same idea as {@link OpenLibraryProvider}, which is
 * what the shared {@link ExternalBook} model is for: two very different payloads, one
 * shape downstream.
 *
 * <p>ponytail: optional API key, because Google Books works keyless for small volumes
 * and a self-hosted library should not have to register anything to get book data.
 * Without a key the quota is shared and easy to exhaust, which is why every failure
 * degrades to an empty result.
 */
public class GoogleBooksProvider implements IsbnProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleBooksProvider.class);

    /** Cover sizes, largest first: a small cover in the catalogue looks broken. */
    private static final List<String> COVER_SIZES =
            List.of("extraLarge", "large", "medium", "small", "smallThumbnail", "thumbnail");

    private final RestClient http;
    private final String apiKey;

    public GoogleBooksProvider(RestClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "googlebooks";
    }

    /** Never throws: an unhappy provider is simply "no data". */
    @Override
    public Optional<ExternalBook> lookup(Isbn isbn) {
        if (isbn == null) {
            return Optional.empty();
        }
        try {
            return fetch(isbn);
        } catch (Exception e) {
            log.warn("Google Books lookup failed for {}: {}", isbn.normalised(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<ExternalBook> fetch(Isbn isbn) {
        JsonNode root = http.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/books/v1/volumes")
                            .queryParam("q", "isbn:" + isbn.normalised());
                    if (apiKey != null && !apiKey.isBlank()) {
                        uriBuilder.queryParam("key", apiKey);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(JsonNode.class);

        if (root == null) {
            return Optional.empty();
        }
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return Optional.empty();
        }

        JsonNode info = items.get(0).path("volumeInfo");
        if (!info.hasNonNull("title")) {
            return Optional.empty();
        }

        return Optional.of(toExternalBook(isbn, info));
    }

    private ExternalBook toExternalBook(Isbn isbn, JsonNode info) {
        List<String> authors = new ArrayList<>();
        for (JsonNode author : info.path("authors")) {
            String name = author.asText(null);
            if (name != null && !name.isBlank()) {
                authors.add(name);
            }
        }

        List<String> categories = new ArrayList<>();
        for (JsonNode category : info.path("categories")) {
            String name = category.asText(null);
            if (name != null && !name.isBlank()) {
                categories.add(name);
            }
        }

        Integer pages = info.hasNonNull("pageCount") ? info.get("pageCount").asInt() : null;

        return new ExternalBook.Mutable(
                "googlebooks",
                isbn.normalised(),
                info.path("title").asText(null),
                info.path("subtitle").asText(null),
                authors,
                info.path("publisher").asText(null),
                year(info.path("publishedDate").asText(null)),
                categories,
                info.path("language").asText(null),
                pages,
                info.path("description").asText(null),
                cover(info.path("imageLinks")))
                .toRecord();
    }

    /** "1984-07-01", "1984-07" and "1984" all mean 1984. */
    static Integer year(String publishedDate) {
        if (publishedDate == null || publishedDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(publishedDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cover(JsonNode imageLinks) {
        for (String size : COVER_SIZES) {
            String url = imageLinks.path(size).asText(null);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return null;
    }
}
