package com.digithon.assignment.repository;

import com.digithon.assignment.domain.ReservationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationEventRepository extends JpaRepository<ReservationEvent, Long> {
}