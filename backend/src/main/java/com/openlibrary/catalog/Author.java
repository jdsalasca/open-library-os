package com.openlibrary.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "authors")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** Derived on creation so "García Márquez, Gabriel" is a plain sort. */
    @Column(name = "sort_name")
    private String sortName;

    @Column(columnDefinition = "text")
    private String bio;

    protected Author() {
    }

    public Author(String name) {
        this.name = name;
        this.sortName = Book.sortNameOf(name);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSortName() {
        return sortName;
    }

    public String getBio() {
        return bio;
    }
}