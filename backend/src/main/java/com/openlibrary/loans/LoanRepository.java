package com.openlibrary.loans;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    Optional<Loan> findFirstByCopyIdAndReturnedAtIsNull(Long copyId);

    List<Loan> findByUserIdAndReturnedAtIsNull(Long userId);

    long countByUserIdAndReturnedAtIsNull(Long userId);

    /** Open loans whose due day is already past: the desk's "who is late" list. */
    @Query("""
            select l from Loan l
            where l.returnedAt is null and l.dueAt < :before
            order by l.dueAt
            """)
    List<Loan> findOverdue(@Param("before") Instant before);

    @Query("""
            select count(l) from Loan l
            where l.userId = :userId and l.returnedAt is null and l.dueAt < :before
            """)
    long countOverdueFor(@Param("userId") Long userId, @Param("before") Instant before);

    @Query("""
            select l from Loan l
            where l.userId = :userId
            order by l.borrowedAt desc
            """)
    Page<Loan> search(@Param("userId") Long userId, Pageable pageable);

    /** What the desk needs by default: only what is still out. */
    @Query("""
            select l from Loan l
            where l.returnedAt is null and (:userId is null or l.userId = :userId)
            order by l.dueAt
            """)
    Page<Loan> findOpen(@Param("userId") Long userId, Pageable pageable);

    /** History: loans already given back, most recent first. */
    @Query("""
            select l from Loan l
            where l.returnedAt is not null and (:userId is null or l.userId = :userId)
            order by l.returnedAt desc
            """)
    Page<Loan> findClosed(@Param("userId") Long userId, Pageable pageable);
}
