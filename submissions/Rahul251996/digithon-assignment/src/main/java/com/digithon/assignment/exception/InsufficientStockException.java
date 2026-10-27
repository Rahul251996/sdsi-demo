package com.digithon.assignment.exception;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String message) {
        super(message, "INSUFFICIENT_STOCK", 409);
    }
}