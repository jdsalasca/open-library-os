package com.openlibrary.loans;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByBookIdAndUserIdAndFulfilledAtIsNullAndCancelledAtIsNull(
            Long bookId, Long userId);

    /** The waiting line for a book, oldest first. */
    @Query("""
            select r from Reservation r
            where r.bookId = :bookId and r.fulfilledAt is null and r.cancelledAt is null
            order by r.createdAt
            """)
    List<Reservation> queueFor(@Param("bookId") Long bookId);

    @Query("""
            select r from Reservation r
            where r.userId = :userId
            order by r.createdAt desc
            """)
    List<Reservation> findByUser(@Param("userId") Long userId);
}
