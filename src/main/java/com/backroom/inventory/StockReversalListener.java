package com.backroom.inventory;

import java.util.UUID;

/**
 * Lets other modules keep their own records consistent when a stock operation is reversed, without inventory
 * depending on them (restocking uses it to reopen a request whose delivery was reversed).
 * Called inside the reversal transaction, after the reversal is claimed and before any balance is locked,
 * so implementations may lock their own rows without inverting the lock order. Throwing aborts the reversal.
 */
public interface StockReversalListener {
    void onReversal(UUID organizationId, UUID reversedOperationId);
}
