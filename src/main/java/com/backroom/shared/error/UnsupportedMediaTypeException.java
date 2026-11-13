package com.backroom.shared.error;

/** An upload is not in a format this endpoint accepts. */
public class UnsupportedMediaTypeException extends DomainException {
    public UnsupportedMediaTypeException(String errorCode, String message) {
        super(errorCode, message);
    }
}
