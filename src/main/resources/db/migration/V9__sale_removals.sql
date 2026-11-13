-- Phase 6: items occasionally sold leave stock as a SALE removal. No prices, customers or orders are recorded.
ALTER TABLE stock_operations DROP CONSTRAINT stock_operations_type_check;
ALTER TABLE stock_operations ADD CONSTRAINT stock_operations_type_check
    CHECK (type IN ('OPENING','RECEIPT','USAGE','WASTE','SALE','TRANSFER','COUNT','REVERSAL'));
