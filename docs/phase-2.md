# Phase 2 inventory correctness

## What is available

Backroom records how much of each item is at each location. Every change is a named operation (receipt, usage, waste, transfer, count, starting quantity or reversal) that writes an immutable movement history. Balances are never edited directly. The catalog tab is now **Inventory**: the list shows the on-hand total, and item detail shows stock by location, the actions people can take there, and the item's history.

PRD v1 is preserved. This document records how Phase 2 implements it and where it deliberately differs.

## Roles

| Action | Owner | Manager | Employee |
| --- | :---: | :---: | :---: |
| View balances and item movement history | Yes | Yes | Yes |
| Receive stock | Yes | Yes | **Yes** |
| Record usage or waste | Yes | Yes | Yes |
| Transfer, count, set starting quantity | Yes | Yes | No |
| Reverse an operation | Yes | Yes | No |
| View organization activity (audit) | Yes | Yes | No |

**Difference from PRD §9:** the PRD keeps receiving with Owners and Managers. Backroom lets Employees receive deliveries too, because the staff who unpack a delivery are often not managers. Receipts are fully attributed and reversible by a manager.

## Quantities

- Exact decimals (`BigDecimal` / `numeric(15,3)`), in the item's base unit. No conversion.
- **Each** takes whole numbers. Gram, kilogram, milliliter and liter take up to three decimal places.
- Commands need a quantity greater than zero. A count may be zero.
- Balances can't go below zero (`409 INSUFFICIENT_STOCK`).
- The API returns quantities as plain strings such as `"12.5"`. Clients may send strings or numbers.

## Operations

All commands live under `/api/v1/organizations/{orgId}/inventory` and carry an `operationKey` (UUID) chosen by the client.

| Command | Body | Effect |
| --- | --- | --- |
| `POST /receipts` | itemId, locationId, quantity, reference?, note? | Adds stock. Creates or reactivates the placement if needed. |
| `POST /opening-balances` | itemId, locationId, quantity, note? | First stock at a location. Rejected once that location has any history (`OPENING_ALREADY_RECORDED`). |
| `POST /removals` | itemId, locationId, quantity, reason `USAGE`/`WASTE`, note? | Removes stock. |
| `POST /transfers` | itemId, fromLocationId, toLocationId, quantity, note? | One operation, two movements. Both sides commit or neither does. Same source and destination: `400 SAME_LOCATION`. |
| `POST /counts` | itemId, locationId, countedQuantity, expectedBalanceVersion, note? | Records the observation. Adds one correcting movement when the count differs; none when it matches. A balance that changed since it was read: `409 STALE_COUNT`. Location not listed for the item: `409 NOT_PLACED`. |
| `POST /operations/{id}/reverse` | note? | Appends the opposite movements as a `REVERSAL` linked to the original. Counts and reversals can't be reversed (`NOT_REVERSIBLE`). An operation is reversed at most once (`ALREADY_REVERSED`). Fails with `INSUFFICIENT_STOCK` if the stock has since been used or moved. |

Reads:

| Endpoint | Returns |
| --- | --- |
| `GET /balances?itemId=` | One row per active placement (plus any slot still holding stock): location, parent name, quantity, balance version, whether it has history. |
| `GET /movements?itemId=&locationId=&page=&size=` | Paged history, newest first, with operation type, actor, note, reference and whether the operation was reversed. |
| `GET /operations/{id}` | One operation with its movements and count result. |

`GET /items` and `GET /items/{id}` include `onHand` (total across locations). `GET /locations/{id}/items` includes `onHand` at that location.

## Idempotency

- A retry with the same `operationKey`, same actor and same contents returns the original result with `200` and `replayed: true`. A new operation returns `201`.
- The same key with different contents or a different actor: `409 IDEMPOTENCY_KEY_REUSED`, with no effect.
- Two identical requests at the same moment: the database unique key lets one win. The other's transaction rolls back, and it answers with the winner's result.
- The web client creates the key when a stock sheet opens and reuses it for every retry from that sheet.

## Placements and locations

- Every placement has exactly one balance. The V4 migration created empty balances for Phase 1 placements.
- Receiving, transferring in, setting starting stock or reversing a removal into an active leaf location creates or reactivates its placement automatically.
- While stock remains, these are rejected:
  - archiving the item (`ITEM_HAS_STOCK`)
  - removing the placement (`PLACEMENT_HAS_STOCK`)
  - archiving the location or giving it child locations (`LOCATION_HAS_STOCK`)
- An item's unit can't change once it has movements (`UNIT_LOCKED`).

## History and audit

- `stock_operations`, `stock_movements` and `stock_counts` reject `UPDATE` and `DELETE` with a database trigger. Mistakes are corrected by reversal.
- For every slot, the sum of its movements equals its balance. Tests assert this.
- Each operation also writes an audit event (`STOCK_RECEIVED`, `STOCK_OPENED`, `STOCK_USED`, `STOCK_WASTED`, `STOCK_TRANSFERRED`, `STOCK_COUNTED`, `STOCK_REVERSED`) with the item name, quantity, unit and locations. Activity shows these to Owners and Managers.
- Employees see item movement history but not organization activity.

## Concurrency

Stock commands don't take the organization-wide lock that Phase 1 writes use, so several people can record stock at once. Each command, in one transaction:

1. checks membership and role;
2. replays a known operation key;
3. share-locks the item row (it can't be archived or have its unit changed meanwhile);
4. claims the key by inserting the operation;
5. share-locks destination locations, inserts if missing, then exclusively locks the placement and the balance;
6. locks balances in ascending location-id order, so opposing transfers can't deadlock;
7. validates, then updates balances and appends movements and audit.

Archiving an item or location, and adding a child location, take exclusive row locks first. They therefore wait for in-flight stock commands and then see the stock those commands committed.

## Verification

- `InventoryIntegrationTests` (PostgreSQL via Testcontainers) cover:
  - every operation and its error codes;
  - idempotent retries and reuse;
  - a concurrent duplicate receipt;
  - concurrent removals beyond stock;
  - opposing transfers;
  - rollback when a transfer fails;
  - archive racing a receipt;
  - roles and tenant isolation;
  - immutable history.
- Competing commands are coordinated with a test hook that holds one transaction open until `pg_stat_activity` shows the other waiting on a lock. No test relies on sleeps.
- `QuantitiesTests` cover unit rules, formatting and operation fingerprints.
- `frontend/e2e/inventory.spec.ts`:
  - An owner receives, transfers, counts and reverses stock.
  - An employee on a 390 px phone records usage and sees the insufficient-stock error.
  - The page never scrolls sideways.
