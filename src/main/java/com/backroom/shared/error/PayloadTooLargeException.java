package com.backroom.shared.error;

/** An upload or requested result is larger than the limit for it. */
public class PayloadTooLargeException extends DomainException {
    public PayloadTooLargeException(String errorCode, String message) {
        super(errorCode, message);
    }
}
