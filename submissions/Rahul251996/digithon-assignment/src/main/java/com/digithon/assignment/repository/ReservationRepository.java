package com.digithon.assignment.repository;

import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") String id);

    /**
     * Sum of quantities for active (RESERVED) reservations for a given user and SKU.
     * Includes only reservations that have not expired.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'RESERVED' AND r.expiresAt > :now")
    int sumActiveReservedQuantity(@Param("userId") String userId,
                                   @Param("sku") String sku,
                                   @Param("now") Instant now);

    /**
     * Sum of quantities for CONFIRMED reservations for a given user and SKU.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'CONFIRMED'")
    int sumConfirmedQuantity(@Param("userId") String userId, @Param("sku") String sku);

    /**
     * Find all expired RESERVED reservations for batch cleanup.
     */
    @Query("SELECT r FROM Reservation r WHERE r.status = 'RESERVED' AND r.expiresAt <= :now")
    List<Reservation> findExpiredReservations(@Param("now") Instant now);

    /**
     * Find expired RESERVED reservations with a limit (for batch processing).
     */
    @Query("SELECT r FROM Reservation r WHERE r.status = 'RESERVED' AND r.expiresAt <= :now " +
           "ORDER BY r.expiresAt ASC")
    List<Reservation> findExpiredReservationsWithLimit(@Param("now") Instant now,
                                                        org.springframework.data.domain.Pageable pageable);

    /**
     * Atomically update status from RESERVED to EXPIRED for expired reservations.
     * Returns count of updated rows.
     */
    @Modifying
    @Query("UPDATE Reservation r SET r.status = 'EXPIRED' WHERE r.status = 'RESERVED' AND r.expiresAt <= :now")
    int expireReservations(@Param("now") Instant now);

    /**
     * Find all reservations for a user and SKU (for locking during concurrent operations).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.userId = :userId AND r.sku = :sku")
    List<Reservation> findByUserIdAndSkuForUpdate(@Param("userId") String userId,
                                                    @Param("sku") String sku);
}