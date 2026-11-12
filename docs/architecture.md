# Backroom architecture

Backroom is a Java 21 / Spring Boot modular monolith backed by PostgreSQL.
React builds to static assets inside the same executable JAR. Development uses
Vite's proxy; deployment needs one Java process and one database.

## Planned module ownership

| Package | Responsibility |
| --- | --- |
| access | Authentication, organizations, memberships, invitations |
| locations | Storage hierarchy and addresses |
| inventory | Items, placements, balances, movements, transfers, counts |
| restocking | Requests, review, actual receipt |
| labels | Code resolution, generation, printable PDFs |
| search | One search across items, locations and codes, through other modules' query services |
| exports | Read-only CSV reports (the one module allowed to read other modules' tables with SQL) |
| audit | Durable business activity |
| assets | Equipment, when that capability ships |
| shared | Small technical configuration and primitives |

Phase 1 implements access, locations, catalog/placements within inventory, audit,
and shared technical configuration. Phase 2 adds balances, stock operations and
movement history to inventory. Phase 3 adds the labels module (codes, scan
resolution, label PDFs). Phase 4 adds restocking (requests, approval,
deliveries). Phase 5 adds low-stock minimums to inventory, the search and
exports modules, and owner-issued password reset links to access. Assets remain
a future module. See [Phase 1](phase-1.md), [Phase 2](phase-2.md),
[Phase 3](phase-3.md), [Phase 4](phase-4.md) and [Phase 5](phase-5.md) for the
implemented contracts.

Controllers will accept DTOs and call application services. Services will enforce
organization access and own transaction boundaries. Inventory will own every
balance change; restocking will use inventory services. Modules should not bypass
one another through repositories. JPA entities are not API responses.

## Decisions

- Spring Boot 4.1.2, Spring MVC, Security, JPA, Validation, Actuator, Flyway.
- Java 21 is enforced by Maven; Windows setup uses an ignored portable JDK.
- PostgreSQL 17 is used in Compose and Testcontainers.
- Flyway applies the access, catalog/locations, audit, inventory, resource-code, and restocking migrations;
  Hibernate uses validate, never update or create.
- No default or demo account exists. Users register through the application;
  Spring's generated user remains disabled.
- GET /actuator/health returns status only. Administrative endpoints are unexposed
  and denied even if a future caller is authenticated.
- API routes require authentication except explicit registration/login/CSRF
  entry points. CSRF stays enabled. Roles are checked against current memberships.
- Sessions will use secure HttpOnly cookies behind production HTTPS. Local HTTP
  leaves the Secure flag off; set SESSION_COOKIE_SECURE=true for HTTPS deployment.
- The frontend uses declarative React Router and a shared session/CSRF-aware API
  client. Organization identity stays in the URL; requests do not use polling.
- Runtime image runs as a non-root user. PostgreSQL data resides in a named volume.
- No Redis, message broker, object storage, external font, or paid API is needed.

## Stock concurrency and idempotency (Phase 2)

- Inventory owns every balance change through StockService. There is no endpoint
  that edits a balance.
- Stock commands use row locks instead of the organization-wide lock, so staff
  recording stock don't queue behind each other. Lock order:
  1. item `FOR SHARE`
  2. destination locations `FOR SHARE`
  3. placement `FOR UPDATE`
  4. balances `FOR UPDATE` in ascending location id
- Archiving an item or location, removing a placement, and adding a child location
  take exclusive row locks on the same rows first, so they serialize with
  in-flight stock commands.
- Each command carries a client operation key, unique per organization. A known
  key replays the stored result. A concurrent duplicate loses on the unique
  constraint; its transaction rolls back and a non-transactional facade
  (StockCommands) answers with the winner's result.
- Movements, operations and counts are insert-only. A database trigger rejects
  updates and deletes. Corrections are linked reversal operations.
- Balances are numeric(15,3) with a non-negative check. Each placement has
  exactly one balance.
- Tests coordinate competing transactions with the StockCommandHooks extension
  point and pg_stat_activity, never with sleeps.

## Labels and scanning (Phase 3)

- `labels` reads items and locations only through `ItemQueries` and
  `LocationQueries`. Inventory and locations never depend on labels.
- `resource_codes` targets exactly one item or location.
  - Generated tokens are globally unique and never recycled.
  - Manufacturer barcodes are unique per organization while active.
  - A trigger makes codes permanent apart from one-way revocation.
- A printed QR holds `{BACKROOM_PUBLIC_URL}/s/{token}`. `/s/{token}` is served
  by the SPA. It calls `GET /api/v1/codes/{token}`, which needs a session and an
  active membership. Signed-out visitors go through `/signin?next=` and come back.
- Label PDFs are generated in memory with ZXing (QR) and PDFBox. Nothing is stored.
- The browser scanner uses the native `BarcodeDetector` when present.
  Otherwise it uses a lazy-loaded zxing-cpp WebAssembly polyfill served from
  `/assets` (no CDN).

## Restocking (Phase 4)

- `restocking` changes stock only by calling `StockService.receive` inside its
  own transaction. The delivery, stock movement, balance, request state and
  audit commit together.
- Lock order for a delivery:
  1. request row (`FOR UPDATE`);
  2. then, inside inventory, the item (shared), the location (shared), the
     placement and the balance.
- The operation key is re-checked under the request lock, so a duplicate
  delivery that waited behind the original replays instead of
  double-counting or failing as an over-receipt.
- Inventory defines `StockReversalListener` and calls it during a reversal,
  before locking balances. Restocking implements it to take a reversed
  delivery off its request. Inventory never depends on restocking (the same
  pattern as `LocationMutationPolicy`).

## Usability (Phase 5)

- **Low stock:** a slot is low when `quantity < minimum_quantity` on an active
  placement of an unarchived item. It is computed in queries, never stored.
- **Home:** composed in the browser from existing endpoints. The open request
  beside a low slot comes from the restocking API, not from inventory, so the
  dependency direction is unchanged.
- **`search`:** depends on `ItemQueries`, `LocationQueries` and
  `ScanService.resolve` only.
- **`exports`:** the documented exception to "no reading across modules". It runs
  read-only SQL over inventory, locations and restocking tables, filtering every
  joined table by `organization_id`, and streams the result in one read-only
  transaction. Nothing may depend on `exports`, and it writes only its own
  audit event.
- **Password resets:**
  - Live in `access`.
  - Sessions carry the account's `credentials_version` from sign-in. A filter
    before authorization rejects a session whose version is stale
    (`401 SESSION_ENDED`). Versions are cached for 30 seconds per user, and
    the reset clears the cache before and after its commit.

POS automation and equipment remain later product capabilities.

See ../backroom_prd_v1.md for the approved product requirements.
