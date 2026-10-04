package com.openlibrary.isbn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fills a book from Open Library, which needs no API key.
 *
 * <p>Two rules drive the shape of this class:
 *
 * <ul>
 *   <li><b>Never fail the caller.</b> A librarian typing an ISBN must still be able to
 *       enter the book by hand, so every failure becomes an empty result, never an error.
 *       A self-hosted instance cannot assume it has internet access at all.
 *   <li><b>Only normalise; do not guess.</b> A field the provider did not send stays null.
 *       {@code pages} of 0 means "the provider claims zero", which differs from unknown.
 * </ul>
 *
 * <p>ponytail: synchronous, one provider, no cache yet. The catalog endpoint that calls
 * this runs on a request thread; add a cache and Google Books when there is a second
 * caller that needs them.
 */
public class OpenLibraryProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenLibraryProvider.class);

    private static final String COVER_BASE = "https://covers.openlibrary.org/b/id/";

    private final RestClient http;

    public OpenLibraryProvider(RestClient http) {
        this.http = http;
    }

    /** Never throws: an unreachable or unhappy provider is simply "no data". */
    public Optional<ExternalBook> lookup(Isbn isbn) {
        if (isbn == null) {
            // A failed checksum must not reach the network.
            return Optional.empty();
        }
        try {
            return fetch(isbn);
        } catch (Exception e) {
            log.warn("Open Library lookup failed for {}: {}", isbn.normalised(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<ExternalBook> fetch(Isbn isbn) {
        JsonNode root = http.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/books")
                        .queryParam("bibkeys", "ISBN:" + isbn.normalised())
                        .queryParam("format", "json")
                        .queryParam("jscmd", "data")
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (root == null) {
            return Optional.empty();
        }

        // The payload is keyed by the exact bibkey that was asked for.
        JsonNode node = root.get("ISBN:" + isbn.normalised());
        // Open Library answers 200 with an empty object when nothing matches.
        if (node == null || !node.hasNonNull("title")) {
            return Optional.empty();
        }

        return Optional.of(toExternalBook(isbn, node));
    }

    private ExternalBook toExternalBook(Isbn isbn, JsonNode node) {
        List<String> authors = new ArrayList<>();
        for (JsonNode author : node.path("authors")) {
            String name = author.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                authors.add(name);
            }
        }

        List<String> subjects = new ArrayList<>();
        for (JsonNode subject : node.path("subjects")) {
            String name = subject.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                subjects.add(name);
            }
        }

        Integer pages = node.hasNonNull("number_of_pages")
                ? node.get("number_of_pages").asInt()
                : null;

        return new ExternalBook.Mutable(
                isbn.normalised(),
                node.path("title").asText(null),
                node.path("subtitle").asText(null),
                authors,
                firstPublisher(node),
                year(node.path("publish_date").asText(null)),
                subjects,
                language(node),
                pages,
                description(node),
                cover(node))
                .toRecord();
    }

    private static String firstPublisher(JsonNode node) {
        for (JsonNode publisher : node.path("publishers")) {
            String name = publisher.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    /** "1984-07" and "1984" both mean 1984; anything unparseable stays unknown. */
    static Integer year(String publishDate) {
        if (publishDate == null || publishDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(publishDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String description(JsonNode node) {
        JsonNode description = node.path("description");
        if (description.isTextual()) {
            return description.asText(null);
        }
        return description.path("value").asText(null);
    }

    /** Prefers the large cover, falls back to small, and accepts a bare cover_id. */
    private static String cover(JsonNode node) {
        JsonNode cover = node.path("cover");
        String large = cover.path("large").asText(null);
        if (large != null && !large.isBlank()) {
            return large;
        }
        String small = cover.path("small").asText(null);
        if (small != null && !small.isBlank()) {
            return small;
        }
        long coverId = node.path("cover_id").asLong(0);
        return coverId > 0 ? COVER_BASE + coverId + "-L.jpg" : null;
    }

    private static String language(JsonNode node) {
        for (JsonNode language : node.path("languages")) {
            String key = language.path("key").asText(null);
            if (key != null && !key.isBlank()) {
                return switch (key) {
                    case "spa" -> "es";
                    case "fre", "fra" -> "fr";
                    case "ger", "deu" -> "de";
                    case "ita" -> "it";
                    case "por" -> "pt";
                    default -> key;
                };
            }
        }
        return null;
    }
}
