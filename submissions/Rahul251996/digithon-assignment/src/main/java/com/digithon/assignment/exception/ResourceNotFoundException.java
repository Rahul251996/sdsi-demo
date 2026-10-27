package com.digithon.assignment.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(message, "NOT_FOUND", 404);
    }
}