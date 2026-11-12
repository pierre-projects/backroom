package com.backroom.inventory;

import com.backroom.shared.PageResponse;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Business commands for stock. There is deliberately no endpoint that edits a balance directly. */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/inventory")
class StockController {
    private final StockCommands commands;
    private final StockService stock;
    StockController(StockCommands commands, StockService stock) { this.commands=commands; this.stock=stock; }

    @PostMapping("/receipts")
    ResponseEntity<StockDtos.OperationView> receive(@PathVariable UUID orgId, @Valid @RequestBody StockDtos.Receipt input) {
        return created(commands.receive(orgId, input));
    }
    @PostMapping("/opening-balances")
    ResponseEntity<StockDtos.OperationView> opening(@PathVariable UUID orgId, @Valid @RequestBody StockDtos.Opening input) {
        return created(commands.openingBalance(orgId, input));
    }
    @PostMapping("/removals")
    ResponseEntity<StockDtos.OperationView> remove(@PathVariable UUID orgId, @Valid @RequestBody StockDtos.Removal input) {
        return created(commands.remove(orgId, input));
    }
    @PostMapping("/transfers")
    ResponseEntity<StockDtos.OperationView> transfer(@PathVariable UUID orgId, @Valid @RequestBody StockDtos.Transfer input) {
        return created(commands.transfer(orgId, input));
    }
    @PostMapping("/counts")
    ResponseEntity<StockDtos.OperationView> count(@PathVariable UUID orgId, @Valid @RequestBody StockDtos.Count input) {
        return created(commands.count(orgId, input));
    }
    @PostMapping("/operations/{operationId}/reverse")
    ResponseEntity<StockDtos.OperationView> reverse(@PathVariable UUID orgId, @PathVariable UUID operationId,
                                                    @Valid @RequestBody StockDtos.Reverse input) {
        return created(commands.reverse(orgId, operationId, input));
    }
    @GetMapping("/operations/{operationId}")
    StockDtos.OperationView operation(@PathVariable UUID orgId, @PathVariable UUID operationId) {
        return stock.operation(orgId, operationId);
    }
    @GetMapping("/balances")
    List<StockDtos.BalanceView> balances(@PathVariable UUID orgId, @RequestParam UUID itemId) {
        return stock.balances(orgId, itemId);
    }
    @GetMapping("/movements")
    PageResponse<StockDtos.MovementView> movements(@PathVariable UUID orgId, @RequestParam(required=false) UUID itemId,
            @RequestParam(required=false) UUID locationId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return stock.movements(orgId, itemId, locationId, page, size);
    }

    /** 201 for a newly applied operation; 200 when a retry returns the original result. */
    private static ResponseEntity<StockDtos.OperationView> created(StockDtos.OperationView view) {
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }
}
