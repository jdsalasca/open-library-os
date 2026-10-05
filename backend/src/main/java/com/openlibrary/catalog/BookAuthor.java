package com.openlibrary.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Credit link between a book and an author, carrying the role and the order.
 *
 * <p>Modelled as an entity rather than a plain join table because the credit role
 * and its position are real data, not noise.
 */
@Entity
@Table(name = "book_authors")
public class BookAuthor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Author author;

    @Column(nullable = false)
    private String role = "AUTOR";

    @Column(nullable = false)
    private int position;

    protected BookAuthor() {
    }

    public BookAuthor(Book book, Author author, String role, int position) {
        this.book = book;
        this.author = author;
        this.role = role;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Book getBook() {
        return book;
    }

    public Author getAuthor() {
        return author;
    }

    public String getRole() {
        return role;
    }

    public int getPosition() {
        return position;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BookAuthor link)) {
            return false;
        }
        return Objects.equals(role, link.role)
                && author != null && Objects.equals(author.getId(), link.author.getId())
                && book != null && Objects.equals(book.getId(), link.book.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, author == null ? null : author.getId(),
                book == null ? null : book.getId());
    }
}
