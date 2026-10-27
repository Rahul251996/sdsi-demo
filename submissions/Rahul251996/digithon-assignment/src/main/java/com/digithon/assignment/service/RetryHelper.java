package com.digithon.assignment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Executes operations with retry for transient database errors
 * (deadlocks, lock timeouts) using Spring's REQUIRES_NEW propagation
 * so each attempt runs in a fresh transaction.
 */
@Component
public class RetryHelper {

    private static final Logger log = LoggerFactory.getLogger(RetryHelper.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 50;

    /**
     * Execute an operation with retry on transient database errors.
     * Each retry runs in a new transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public <T> T executeWithRetry(Supplier<T> operation) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (CannotAcquireLockException | DataIntegrityViolationException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Transient DB error (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_ATTEMPTS, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
                backoff *= 2;
            }
        }
        throw new RuntimeException("Unreachable");
    }
}