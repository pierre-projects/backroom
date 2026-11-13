package com.backroom.inventory;

import java.util.UUID;

/**
 * Extension point invoked inside a stock transaction right after its balances are locked.
 * Production uses the no-op implementation; tests replace it to hold a transaction open and force
 * competing commands to overlap deterministically instead of relying on timing.
 */
public interface StockCommandHooks {
    void afterBalancesLocked(StockOperationType type, UUID itemId);
}
