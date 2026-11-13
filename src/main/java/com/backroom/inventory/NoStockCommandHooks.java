package com.backroom.inventory;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class NoStockCommandHooks implements StockCommandHooks {
    @Override
    public void afterBalancesLocked(StockOperationType type, UUID itemId) {}
}
