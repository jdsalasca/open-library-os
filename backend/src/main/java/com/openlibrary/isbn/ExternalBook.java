package com.openlibrary.isbn;

import java.util.List;

/**
 * A book as returned by an outside provider, normalised so the catalogue and the
 * UI only ever see one shape regardless of which API answered.
 *
 * <p>{@code source} records where the data came from, because the autofill screen
 * shows the user which provider supplied each field.
 */
public record ExternalBook(
        String isbn,
        String title,
        String subtitle,
        List<String> authors,
        String publisher,
        Integer publicationYear,
        List<String> categories,
        String language,
        Integer pages,
        String summary,
        String coverUrl,
        String source
) {

    /**
     * Builder that applies the cleanup every provider would otherwise repeat:
     * trim everything, and treat a blank string as absent.
     *
     * <p>{@code pages} is a boxed {@link Integer} on purpose: 0 pages is a (bad) claim
     * from the provider, while {@code null} is "unknown", and the catalogue must be able
     * to tell those apart.
     */
    public static final class Mutable {
        private final String isbn;
        private final String title;
        private final String subtitle;
        private final List<String> authors;
        private final String publisher;
        private final Integer publicationYear;
        private final List<String> categories;
        private final String language;
        private final Integer pages;
        private final String summary;
        private final String coverUrl;
        private final String source;

        public Mutable(String isbn, String title, String subtitle, List<String> authors,
                       String publisher, Integer publicationYear, List<String> categories,
                       String language, Integer pages, String summary, String coverUrl) {
            this.isbn = Isbn.parse(isbn) == null ? isbn : Isbn.parse(isbn).normalised();
            this.title = clean(title);
            this.subtitle = clean(subtitle);
            this.authors = cleanAll(authors);
            this.publisher = clean(publisher);
            this.publicationYear = publicationYear;
            this.categories = cleanAll(categories);
            this.language = clean(language);
            this.pages = pages;
            this.summary = clean(summary);
            this.coverUrl = clean(coverUrl);
            this.source = "openlibrary";
        }

        public ExternalBook toRecord() {
            return new ExternalBook(isbn, title, subtitle, authors, publisher,
                    publicationYear, categories, language, pages, summary, coverUrl, source);
        }

        private static String clean(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static List<String> cleanAll(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                    .map(Mutable::clean)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
    }
}
