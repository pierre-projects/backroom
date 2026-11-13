package com.backroom.shared.error;

/** The request itself is unusable: a value is missing, malformed or out of range. */
public class InvalidRequestException extends DomainException {
    public InvalidRequestException(String message) {
        this("VALIDATION_ERROR", message);
    }

    public InvalidRequestException(String errorCode, String message) {
        super(errorCode, message);
    }
}
