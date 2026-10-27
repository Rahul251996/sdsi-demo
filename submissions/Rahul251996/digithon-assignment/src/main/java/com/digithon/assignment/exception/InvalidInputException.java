package com.digithon.assignment.exception;

public class InvalidInputException extends BusinessException {

    public InvalidInputException(String message) {
        super(message, "INVALID_INPUT", 400);
    }
}