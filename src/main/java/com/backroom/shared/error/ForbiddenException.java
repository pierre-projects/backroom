package com.backroom.shared.error;

/** The signed-in person's role does not allow this action. */
public class ForbiddenException extends DomainException {
    public ForbiddenException() {
        super("FORBIDDEN", "Your role does not permit this action.");
    }
}
