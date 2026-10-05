package com.openlibrary.catalog;

import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import com.openlibrary.isbn.Isbn;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isbn13")
    private String isbn13;

    @Column(name = "isbn10")
    private String isbn10;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publisher_id")
    private Publisher publisher;

    @Column(name = "publication_year")
    private Integer publicationYear;

    private String language;

    private Integer pages;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "cover_url")
    private String coverUrl;

    private String edition;

    /** Denormalised search key; see {@link #reindex()}. */
    @Column(name = "search_text")
    private String searchText;

    // OneToMany, not ManyToMany: the credit link is an entity with its own role and
    // position, and owning book_id there keeps ORPHAN_REMOVAL able to drop credits.
    // BatchSize turns the lazy loads into two extra queries per page instead of
    // two per book; join fetching is not an option with two List collections.
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position asc, id asc")
    private List<BookAuthor> authors = new ArrayList<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BookCategory> categories = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Book() {
    }

    public Book(String title) {
        this.title = title;
    }

    /** Normalises the ISBN and remembers whether the staff typed the 10-digit form. */
    public void setIsbn(String raw) {
        this.isbn13 = null;
        this.isbn10 = null;
        if (raw == null || raw.isBlank()) {
            return;
        }
        Isbn parsed = Isbn.parse(raw);
        if (parsed == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_isbn",
                    "El ISBN no es valido.",
                    List.of(new ApiException.FieldError("isbn", "ISBN no valido")));
        }
        this.isbn13 = parsed.normalised();
        if (parsed.isTen()) {
            this.isbn10 = raw.replaceAll("[\\s-]", "");
        }
    }

    public void setDetails(String title, String subtitle, Integer publicationYear, String language,
                           Integer pages, String summary, String coverUrl, String edition,
                           Publisher publisher) {
        this.title = title;
        this.subtitle = subtitle;
        this.publicationYear = publicationYear;
        this.language = language;
        this.pages = pages;
        this.summary = summary;
        this.coverUrl = coverUrl;
        this.edition = edition;
        this.publisher = publisher;
        this.updatedAt = Instant.now();
    }

    /**
     * Replaces the author credits wholesale; the form is the source of truth.
     *
     * <p>The existing collection is mutated rather than reassigned: orphan removal
     * tracks the live instance, and swapping it makes Hibernate complain.
     */
    public void setAuthors(List<Author> ordered) {
        this.authors.clear();
        int position = 0;
        for (Author author : ordered) {
            this.authors.add(new BookAuthor(this, author, "AUTOR", position++));
        }
    }

    public void setCategories(Set<Category> categories) {
        this.categories.clear();
        for (Category category : categories) {
            this.categories.add(new BookCategory(this, category));
        }
    }

    /**
     * Rebuilds the denormalised search text: title, subtitle, ISBN and every author
     * name, folded to lower case without accents. Call after changing any of them.
     */
    public void reindex() {
        var text = new StringBuilder();
        append(text, title);
        append(text, subtitle);
        append(text, isbn13);
        append(text, isbn10);
        for (BookAuthor link : authors) {
            if (link.getAuthor() != null) {
                append(text, link.getAuthor().getName());
            }
        }
        this.searchText = text.toString().trim();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.append(fold(value)).append(' ');
    }

    /** Lower case and without accents, so "marquez" finds "Márquez". */
    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
    }

    /**
     * Sort key for an author: lower case and without accents, so "marquez" and
     * "Márquez" sort together and the staff find them with or without accents.
     * Inverting names ("Márquez, Gabriel") is a library convention, not a
     * requirement here; add it when a librarian asks for it.
     */
    public static String sortNameOf(String fullName) {
        return fold(fullName);
    }

    public String getSearchText() {
        return searchText;
    }

    public Long getId() {
        return id;
    }

    public String getIsbn13() {
        return isbn13;
    }

    public String getIsbn10() {
        return isbn10;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public Publisher getPublisher() {
        return publisher;
    }

    public Integer getPublicationYear() {
        return publicationYear;
    }

    public String getLanguage() {
        return language;
    }

    public Integer getPages() {
        return pages;
    }

    public String getSummary() {
        return summary;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public String getEdition() {
        return edition;
    }

    public List<BookAuthor> getAuthors() {
        return authors;
    }

    public List<BookCategory> getCategories() {
        return categories;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<Long> categoryIds() {
        var ids = new LinkedHashSet<Long>();
        for (BookCategory link : categories) {
            ids.add(link.getCategory().getId());
        }
        return ids;
    }

    public Set<String> categoryNames() {
        var names = new LinkedHashSet<String>();
        for (BookCategory link : categories) {
            names.add(link.getCategory().getName());
        }
        return names;
    }
}
