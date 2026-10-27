package com.digithon.assignment.exception;

public class InvalidStateTransitionException extends BusinessException {

    public InvalidStateTransitionException(String message) {
        super(message, "INVALID_STATE_TRANSITION", 409);
    }
}