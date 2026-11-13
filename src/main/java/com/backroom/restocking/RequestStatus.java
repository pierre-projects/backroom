package com.backroom.restocking;

import java.util.EnumSet;
import java.util.Set;

public enum RequestStatus {
    SUBMITTED,
    APPROVED,
    PARTIALLY_RECEIVED,
    FULFILLED,
    REJECTED,
    CANCELLED,
    CLOSED_SHORT;

    static final Set<RequestStatus> OPEN = EnumSet.of(SUBMITTED, APPROVED, PARTIALLY_RECEIVED);
    static final Set<RequestStatus> RECEIVING = EnumSet.of(APPROVED, PARTIALLY_RECEIVED);
}
