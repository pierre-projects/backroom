package com.backroom.shared.error;

/** The request is well formed but conflicts with the current state, such as a stale version or a business rule. */
public class ConflictException extends DomainException {
    public ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
