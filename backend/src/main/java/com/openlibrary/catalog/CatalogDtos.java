package com.openlibrary.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record AuthorRef(Long id, String name, String role, int position) {
    }

    public record CategoryRef(Long id, String name, String slug) {
    }

    /**
 * The first screen's view of the catalogue: how big it is, what arrived
 * lately, and anything matching what is being typed.
 */
public record Suggestions(
        long totalBooks,
        List<BookSummary> recent,
        List<BookSummary> results) {
}

public record BookSummary(
            Long id,
            String title,
            String subtitle,
            List<AuthorRef> authors,
            String publisher,
            Integer publicationYear,
            String language,
            Integer pages,
            String coverUrl,
            String isbn13) {
    }

    /** Just enough of a book to say "this ISBN is already on the shelf". */
    public record BookRef(Long id, String title) {}
    public record BookDetail(
            Long id,
            String title,
            String subtitle,
            List<AuthorRef> authors,
            List<CategoryRef> categories,
            String publisher,
            Integer publicationYear,
            String language,
            Integer pages,
            String summary,
            String coverUrl,
            String edition,
            String isbn13,
            String isbn10) {
    }

    public record AuthorInput(
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "AUTOR|COAUTOR|TRADUCTOR|ILUSTRADOR|EDITOR",
                    message = "Rol de autor no valido") String role) {
    }

    /**
     * Authors, publisher and categories travel as names. The staff types what they
     * know and the catalogue links or creates the rows, which saves three admin
     * screens for a library that mostly types the same fifty authors.
     */
    public record UpsertBookRequest(
            @NotBlank @Size(max = 300) String title,
            @Size(max = 300) String subtitle,
            String isbn,
            String publisher,
            @Min(1450) Integer publicationYear,
            @Size(max = 40) String language,
            @Min(1) Integer pages,
            @Size(max = 20_000) String summary,
            @Size(max = 500) String coverUrl,
            @Size(max = 120) String edition,
            @NotEmpty(message = "Un libro necesita al menos un autor")
            List<@Valid AuthorInput> authors,
            List<@Size(max = 120) String> categories) {
    }

    public record AuthorSummary(Long id, String name, String sortName) {
    }

    public record PublisherSummary(Long id, String name) {
    }

    /** Uniform page envelope so the frontend never has to guess the shape. */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}