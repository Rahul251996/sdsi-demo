package com.digithon.assignment.controller;

import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.dto.ReservationResponse;
import com.digithon.assignment.service.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    private final InventoryService inventoryService;

    public ReservationController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * M2: Create a reservation.
     */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody ReservationRequest request,
            HttpServletRequest httpRequest) {
        String requestId = (String) httpRequest.getAttribute("requestId");
        log.debug("Create reservation request: user={}, sku={}, qty={}, requestId={}",
                request.getUserId(), request.getSku(), request.getQuantity(), requestId);

        ReservationResponse response = inventoryService.reserve(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * M3: Confirm a reservation.
     */
    @PostMapping("/reservations/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(
            @PathVariable String id,
            HttpServletRequest httpRequest) {
        log.debug("Confirm reservation: id={}", id);
        ReservationResponse response = inventoryService.confirm(id);
        return ResponseEntity.ok(response);
    }

    /**
     * M3: Cancel a reservation.
     */
    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(
            @PathVariable String id,
            HttpServletRequest httpRequest) {
        log.debug("Cancel reservation: id={}", id);
        ReservationResponse response = inventoryService.cancel(id);
        return ResponseEntity.ok(response);
    }

    /**
     * M3: Get a reservation by ID.
     */
    @GetMapping("/reservations/{id}")
    public ResponseEntity<ReservationResponse> getReservation(
            @PathVariable String id,
            HttpServletRequest httpRequest) {
        log.debug("Get reservation: id={}", id);
        ReservationResponse response = inventoryService.getReservation(id);
        return ResponseEntity.ok(response);
    }
}