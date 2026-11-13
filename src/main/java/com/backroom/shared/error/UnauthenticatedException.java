package com.backroom.shared.error;

/** Nobody is signed in. */
public class UnauthenticatedException extends DomainException {
    public UnauthenticatedException() {
        super("UNAUTHENTICATED", "Sign in to continue.");
    }
}
