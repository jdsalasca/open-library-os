package com.openlibrary.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CopyRepository extends JpaRepository<Copy, Long> {

    Optional<Copy> findByBarcode(String barcode);

    Optional<Copy> findByCode(String code);

    long countByBookId(Long bookId);

    long countByBookIdAndStatus(Long bookId, CopyStatus status);

    @Query("""
            select c from Copy c
            where (:status is null or c.status = :status)
              and (:bookId is null or c.bookId = :bookId)
              and (:locationId is null or c.locationId = :locationId)
              and (:q is null
                   or lower(c.code) like :like
                   or lower(c.barcode) like :like
                   or exists (select 1 from Book b
                              where b.id = c.bookId and b.searchText like :like))
            """)
    Page<Copy> search(@Param("q") String q,
                      @Param("like") String like,
                      @Param("status") CopyStatus status,
                      @Param("bookId") Long bookId,
                      @Param("locationId") Long locationId,
                      Pageable pageable);

    /** Availability per book, for the catalogue badge. */
    @Query("""
            select c.bookId, c.status, count(c) from Copy c
            where c.bookId in :bookIds
            group by c.bookId, c.status
            """)
    List<Object[]> countsByBook(List<Long> bookIds);

    List<Copy> findByBookId(Long bookId);

    long countByLocationId(Long locationId);
}