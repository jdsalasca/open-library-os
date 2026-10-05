package com.openlibrary.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByIsbn13(String isbn13);

    /**
     * Filtering happens here; authors and categories are batch-loaded by Hibernate
     * rather than join-fetched, because a single query cannot fetch two List
     * collections (MultipleBagFetchException).
     */
    @Query("""
            select b from Book b
            where (:q is null
                   or b.searchText like :like
                   or b.isbn13 like :digits
                   or b.isbn10 like :digits)
              and (:categoryId is null or exists (select 1 from BookCategory c
                                                  where c.book = b and c.category.id = :categoryId))
              and (:publisherId is null or b.publisher.id = :publisherId)
              and (:year is null or b.publicationYear = :year)
              and (:language is null or lower(b.language) = :language)
            """)
    Page<Book> search(@Param("q") String q,
                      @Param("like") String like,
                      @Param("digits") String digits,
                      @Param("categoryId") Long categoryId,
                      @Param("publisherId") Long publisherId,
                      @Param("year") Integer year,
                      @Param("language") String language,
                      Pageable pageable);

    @Query("select b.publisher from Book b where b.publisher is not null")
    java.util.List<Publisher> usedPublishers();
}