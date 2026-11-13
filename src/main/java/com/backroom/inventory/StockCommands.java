package com.backroom.inventory;

import com.backroom.shared.error.ConflictException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Non-transactional entry point for stock commands.
 * When two requests with the same operation key race, the database unique constraint lets one win; the loser's
 * transaction is rolled back. This facade then answers the loser with the winner's result, in a fresh transaction.
 */
@Service
public class StockCommands {
    private final StockService stock;

    StockCommands(StockService stock) {
        this.stock = stock;
    }

    public StockDtos.OperationView receive(UUID orgId, StockDtos.Receipt in) {
        return run(orgId, in.operationKey(), in.fingerprint(), () -> stock.receive(orgId, in));
    }

    public StockDtos.OperationView openingBalance(UUID orgId, StockDtos.Opening in) {
        return run(orgId, in.operationKey(), in.fingerprint(), () -> stock.openingBalance(orgId, in));
    }

    public StockDtos.OperationView remove(UUID orgId, StockDtos.Removal in) {
        return run(orgId, in.operationKey(), in.fingerprint(), () -> stock.remove(orgId, in));
    }

    public StockDtos.OperationView transfer(UUID orgId, StockDtos.Transfer in) {
        return run(orgId, in.operationKey(), in.fingerprint(), () -> stock.transfer(orgId, in));
    }

    public StockDtos.OperationView count(UUID orgId, StockDtos.Count in) {
        return run(orgId, in.operationKey(), in.fingerprint(), () -> stock.count(orgId, in));
    }

    public StockDtos.OperationView reverse(UUID orgId, UUID operationId, StockDtos.Reverse in) {
        return run(orgId, in.operationKey(), in.fingerprint(operationId), () -> stock.reverse(orgId, operationId, in));
    }

    private StockDtos.OperationView run(
            UUID orgId, UUID key, String fingerprint, Supplier<StockDtos.OperationView> command) {
        try {
            return command.get();
        } catch (DataIntegrityViolationException conflict) {
            var detail = String.valueOf(conflict.getMostSpecificCause().getMessage());
            if (detail.contains("stock_operations_key_uq")) return stock.replayAfterConflict(orgId, key, fingerprint);
            if (detail.contains("stock_operations_reversal_uq")) {
                throw new ConflictException("ALREADY_REVERSED", "This operation has already been reversed.");
            }
            throw conflict;
        }
    }
}
