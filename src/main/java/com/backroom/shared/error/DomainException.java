package com.backroom.shared.error;

/** A business rule refused the request. The error code is part of the API contract; the message is shown to people. */
public abstract class DomainException extends RuntimeException {
    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
