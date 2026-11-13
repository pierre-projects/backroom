# Phase 4 replenishment

## What is available

Anyone in a business can ask for more of one item at one location. Owners and Managers approve the request, optionally changing the quantity once, or reject it with a reason. Whoever unpacks the delivery records it against the request, and stock rises by exactly what arrived. A request stays open until everything approved has arrived or a manager closes it short with a reason.

Approval is internal coordination. It is not a supplier order, and it never changes stock.

Navigation: **Requests** is a tab for every role. Its badge counts requests waiting for review (Owners and Managers) or approved requests waiting for a delivery (Employees). Owners now open **People** and **Business settings** from the account menu (phone) or the **Administration** group in the sidebar (desktop).

## States

| State | Shown as | Next |
| --- | --- | --- |
| `SUBMITTED` | Submitted | approve → `APPROVED`; reject (reason) → `REJECTED`; cancel → `CANCELLED` |
| `APPROVED` | Approved | delivery → `PARTIALLY_RECEIVED` or `FULFILLED`; close short (reason) → `CLOSED_SHORT` |
| `PARTIALLY_RECEIVED` | Partly received | delivery → `PARTIALLY_RECEIVED` or `FULFILLED`; close short → `CLOSED_SHORT` |
| `FULFILLED` | Received | final |
| `REJECTED`, `CANCELLED`, `CLOSED_SHORT` | Rejected, Cancelled, Closed short | final |

## Rules

- **Approval and stock:**
  - A delivery before approval returns `409 NOT_APPROVED`. Any other move the table doesn't allow returns `409 INVALID_TRANSITION`.
  - The approved quantity is set once, when approving, and the request shows both numbers ("Requested 10 · Approved 6").
- **Quantities:** follow the item's unit (whole numbers for Each, up to three decimals otherwise).
- **Over-receipt:**
  - A delivery larger than what is still expected returns `409 OVER_RECEIPT`.
  - Extra stock is recorded as an ordinary receipt on the item.
- **Reasons and cancelling:**
  - Reject and close short require a reason, shown on the request.
  - Cancel is for the requester or a manager, while the request is still `SUBMITTED`.
- **Stale versions:** every decision carries `expectedVersion` and locks the request row; a stale version returns `409 STALE_VERSION`.
- **Safe retries:**
  - Each delivery carries an `operationKey` from the client; the delivery sheet creates it when it opens and reuses it on retry.
  - The key is checked after the request is locked, so a duplicate that waited behind the original replays its result (`200`, `replayed: true`) instead of counting twice.
  - The same key with different contents returns `409 IDEMPOTENCY_KEY_REUSED`.
- **One transaction:** a delivery, its stock movement, the balance, the request and the audit events commit together, or none of them do.
- **History:** deliveries stay visible after closure. `restock_receipts` is insert-only.
- **Reversing a delivery:** reversing it in an item's stock history takes it off the request. A received request reopens (Approved or Partly received); a request closed short stays closed. The delivery stays in the request history, marked as reversed.

## Roles

| Action | Owner | Manager | Employee |
| --- | :---: | :---: | :---: |
| Submit a request, view requests | Yes | Yes | Yes |
| Cancel a submitted request | Yes | Yes | Own only |
| Approve, reject, close short | Yes | Yes | No |
| Record a delivery | Yes | Yes | **Yes** |

Employees recording deliveries follows the Phase 2 decision that they can receive stock. PRD §9 keeps receiving with managers.

## API

All routes are under `/api/v1/organizations/{orgId}/requests`.

| Route | Purpose |
| --- | --- |
| `GET ?filter=NEEDS_REVIEW\|TO_RECEIVE\|MINE\|OPEN\|ALL&itemId=&locationId=&page=` | Newest first |
| `GET /summary` | `{ needsReview, toReceive }` for the tab badge |
| `GET /{id}` | Request with its deliveries (`receipts[]`, each with `reversed`) |
| `POST` | `{ itemId, locationId, quantity, note? }`; the location must be an active leaf |
| `POST /{id}/approve` | `{ expectedVersion, approvedQuantity?, note? }` |
| `POST /{id}/reject`, `/{id}/close-short` | `{ expectedVersion, reason }` |
| `POST /{id}/cancel` | `{ expectedVersion }` |
| `POST /{id}/receipts` | `{ operationKey, quantity, note? }`; `201` new, `200` replayed |

## Design

- `restocking` changes stock only through `StockService.receive`. The stock movement carries the reference `R-n`.
- Lock order for a delivery:
  1. request row;
  2. then, inside `StockService`, the item (shared), the location (shared), the placement and the balance.
- Inventory calls `StockReversalListener` implementations while reversing, before it locks balances. Restocking implements the listener, so inventory never imports restocking.

## Verification

- **`restocking.RestockingIntegrationTests`:**
  - the lifecycle with partial deliveries;
  - approval with a changed quantity, and approval leaving stock unchanged;
  - transition, reason, version and role rules;
  - tenant isolation;
  - idempotent retries;
  - a concurrent duplicate delivery (`201` + `200`);
  - concurrent deliveries over the remainder (`201` + `409`);
  - rollback when a delivery fails;
  - reversal reopening a request;
  - immutable deliveries.
- **`frontend/e2e/requests.spec.ts`:**
  - an employee on a phone requests 10 L and sees the open-request warning on a second try;
  - the owner approves 6 L;
  - the employee records 4 L and 2 L, and the request shows Received with stock at 6 L;
  - the owner closes another request short with a reason.
