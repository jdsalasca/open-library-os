package com.openlibrary.admin;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The shape of a library on disk. Everything that identifies a row uses a natural
 * key instead of a database id, because ids are meaningless on another machine:
 * a publisher is its name, a shelf is its code, a copy is its printed code and a
 * book is its ISBN when it has one.
 *
 * <p>{@code schemaVersion} is what makes an old export readable by a new program:
 * the importer refuses a document it does not understand instead of importing
 * half of it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LibraryDocument(
        int schemaVersion,
        String exportedAt,
        Map<String, Integer> counts,
        List<Map<String, Object>> publishers,
        List<Map<String, Object>> authors,
        List<Map<String, Object>> categories,
        List<Map<String, Object>> books,
        List<Map<String, Object>> locations,
        List<Map<String, Object>> copies,
        List<Map<String, Object>> users,
        List<Map<String, Object>> loans,
        List<Map<String, Object>> reservations,
        List<Map<String, Object>> appConfig) {

    public static final int VERSION = 1;

    public LibraryDocument {
        publishers = publishers == null ? List.of() : publishers;
        authors = authors == null ? List.of() : authors;
        categories = categories == null ? List.of() : categories;
        books = books == null ? List.of() : books;
        locations = locations == null ? List.of() : locations;
        copies = copies == null ? List.of() : copies;
        users = users == null ? List.of() : users;
        loans = loans == null ? List.of() : loans;
        reservations = reservations == null ? List.of() : reservations;
        appConfig = appConfig == null ? List.of() : appConfig;
    }

    /** Empty document, used for a library that has just been installed. */
    public static LibraryDocument empty() {
        return new LibraryDocument(VERSION, "", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
