package com.digithon.assignment.repository;

import com.digithon.assignment.domain.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * Sum of active RESERVED quantities for a user/SKU pair.
     * Only counts reservations that are not yet expired.
     * Index: idx_reservations_user_sku_status
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'RESERVED' AND r.expiresAt > :now")
    int sumActiveReservedQuantity(@Param("userId") String userId,
                                   @Param("sku") String sku,
                                   @Param("now") Instant now);

    /**
     * Sum of CONFIRMED quantities for a user/SKU pair.
     * Index: idx_reservations_user_sku_status
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'CONFIRMED'")
    int sumConfirmedQuantity(@Param("userId") String userId, @Param("sku") String sku);

    // =========================================================================
    // SKU-scoped expiry queries (bounded — do not scan the entire table)
    // =========================================================================

    /**
     * Find expired RESERVED reservations for a single SKU.
     * Uses index: idx_reservations_status_expires_compound (status, expires_at, sku)
     */
    @Query("SELECT r FROM Reservation r " +
           "WHERE r.status = 'RESERVED' AND r.expiresAt <= :now AND r.sku = :sku")
    List<Reservation> findExpiredForSku(@Param("sku") String sku, @Param("now") Instant now);

    /**
     * Find expired RESERVED reservations for a single SKU, excluding one ID.
     */
    @Query("SELECT r FROM Reservation r " +
           "WHERE r.status = 'RESERVED' AND r.expiresAt <= :now AND r.sku = :sku AND r.id <> :excludeId")
    List<Reservation> findExpiredForSkuExcluding(@Param("sku") String sku,
                                                   @Param("now") Instant now,
                                                   @Param("excludeId") String excludeId);

    /**
     * Find expired reservations across all SKUs, batched.
     * Uses index idx_reservations_status_expires_compound.
     */
    @Query("SELECT r FROM Reservation r " +
           "WHERE r.status = 'RESERVED' AND r.expiresAt <= :now " +
           "ORDER BY r.expiresAt ASC")
    List<Reservation> findExpiredWithLimit(@Param("now") Instant now,
                                            org.springframework.data.domain.Pageable pageable);

    /**
     * Find all reservations for a user/SKU pair with pessimistic write lock.
     * Used for purchase-limit coordination.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.userId = :userId AND r.sku = :sku")
    List<Reservation> findByUserIdAndSkuForUpdate(@Param("userId") String userId,
                                                    @Param("sku") String sku);

    // =========================================================================
    // Anti-hoarding queries
    // =========================================================================

    /**
     * Count active (RESERVED, not yet expired) reservations for a user
     * across ALL SKUs. Used to enforce maxActiveReservationsPerUser.
     * Index: idx_reservations_user_sku_status partially covers this
     * (user_id is leading column; status and expires_at filter in-memory).
     */
    @Query("SELECT COUNT(r) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.status = 'RESERVED' AND r.expiresAt > :now")
    int countActiveReservationsByUser(@Param("userId") String userId,
                                      @Param("now") Instant now);

    /**
     * Sum of total quantity in active (RESERVED, not expired) reservations
     * for a user across ALL SKUs. Used for maxTotalReservedQuantityPerUser.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r " +
           "WHERE r.userId = :userId AND r.status = 'RESERVED' AND r.expiresAt > :now")
    int sumActiveReservedQuantityByUser(@Param("userId") String userId,
                                        @Param("now") Instant now);

    /**
     * Find the most recent CANCELLED reservation for a user+SKU pair.
     * Used for cooldown-after-cancel enforcement.
     */
    @Query("SELECT r FROM Reservation r " +
           "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'CANCELLED' " +
           "ORDER BY r.updatedAt DESC")
    List<Reservation> findRecentCancelledByUserAndSku(@Param("userId") String userId,
                                                       @Param("sku") String sku,
                                                       org.springframework.data.domain.Pageable pageable);
}