package com.backroom.inventory;

public enum StockOperationType {
    OPENING, RECEIPT, USAGE, WASTE, TRANSFER, COUNT, REVERSAL;

    /** Counts are corrected by counting again; reversals cannot themselves be reversed. */
    public boolean reversible() { return this != COUNT && this != REVERSAL; }
}
