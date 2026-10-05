package com.openlibrary.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "book_categories")
public class BookCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    protected BookCategory() {
    }

    public BookCategory(Book book, Category category) {
        this.book = book;
        this.category = category;
    }

    public Long getId() {
        return id;
    }

    public Book getBook() {
        return book;
    }

    public Category getCategory() {
        return category;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BookCategory link)) {
            return false;
        }
        return category != null && Objects.equals(category.getId(), link.category.getId())
                && book != null && Objects.equals(book.getId(), link.book.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(category == null ? null : category.getId(),
                book == null ? null : book.getId());
    }
}