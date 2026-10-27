package com.digithon.assignment.exception;

/**
 * Thrown when a user exceeds anti-hoarding limits:
 * - Too many active reservations across SKUs
 * - Too much total reserved quantity
 * - Reservation cooldown after cancel not yet expired
 */
public class HoardingLimitExceededException extends BusinessException {

    public HoardingLimitExceededException(String message) {
        super(message, "HOARDING_LIMIT_EXCEEDED", 429);
    }
}