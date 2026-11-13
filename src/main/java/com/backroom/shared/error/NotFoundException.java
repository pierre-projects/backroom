package com.backroom.shared.error;

/**
 * The resource does not exist or belongs to another organization. Both cases look the same so that ids from other
 * businesses reveal nothing.
 */
public class NotFoundException extends DomainException {
    public NotFoundException() {
        super("NOT_FOUND", "The requested resource is unavailable.");
    }
}
