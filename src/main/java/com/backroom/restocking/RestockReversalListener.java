package com.backroom.restocking;

import com.backroom.inventory.StockReversalListener;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * When a delivery recorded against a request is reversed in stock history, take it off the request:
 * the received quantity drops and a received request reopens. A request closed short stays closed.
 */
@Component
class RestockReversalListener implements StockReversalListener {
    private final RestockRequestRepository requests;
    private final RestockReceiptRepository receipts;
    RestockReversalListener(RestockRequestRepository requests, RestockReceiptRepository receipts) { this.requests=requests; this.receipts=receipts; }

    @Override
    public void onReversal(UUID orgId, UUID reversedOperationId) {
        var receipt = receipts.findByOrganizationIdAndStockOperationId(orgId, reversedOperationId);
        if (receipt.isEmpty()) return;
        var request = requests.lockById(orgId, receipt.get().requestId).orElseThrow();
        request.receivedQuantity = request.receivedQuantity.subtract(receipt.get().quantity);
        request.settle();
        request.updatedAt = Instant.now();
        requests.flush();
    }
}
