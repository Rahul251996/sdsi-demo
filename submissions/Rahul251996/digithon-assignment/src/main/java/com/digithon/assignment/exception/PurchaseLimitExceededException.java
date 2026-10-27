package com.digithon.assignment.exception;

public class PurchaseLimitExceededException extends BusinessException {

    public PurchaseLimitExceededException(String message) {
        super(message, "PURCHASE_LIMIT_EXCEEDED", 409);
    }
}