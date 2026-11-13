# Backroom architecture

Backroom is a Java 21 / Spring Boot modular monolith backed by PostgreSQL.
React builds to static assets inside the same executable JAR. Development uses
Vite's proxy; deployment needs one Java process and one database.

## Planned module ownership

| Package | Responsibility |
| --- | --- |
| access | Authentication, organizations, memberships, invitations; Spring Security setup in `access.security` |
| locations | Storage hierarchy and addresses |
| inventory | Items, placements, balances, movements, transfers, counts |
| restocking | Requests, review, actual receipt |
| labels | Code resolution, generation, printable PDFs |
| search | One search across items, locations and codes, through other modules' query services |
| exports | Read-only CSV reports (the one module allowed to read other modules' tables with SQL) |
| imports | CSV import of items, places and starting quantities; writes only through other modules' services |
| audit | Durable business activity |
| assets | Equipment, when that capability ships |
| shared | Technical primitives only, depending on no feature module: `error` (business exceptions services throw), `persistence` (base entities), `web` (HTTP error mapping, paging, request ids, SPA routes), `config` (application and production settings) |

Phase 1 implements access, locations, catalog/placements within inventory, audit,
and shared technical configuration. Phase 2 adds balances, stock operations and
movement history to inventory. Phase 3 adds the labels module (codes, scan
resolution, label PDFs). Phase 4 adds restocking (requests, approval,
deliveries). Phase 5 adds low-stock minimums to inventory, the search and
exports modules, and owner-issued password reset links to access. Phase 6
adds location trails and counts, a Sold removal and item photos. Phase 7 adds
the imports module. Assets remain
a future module. See [Phase 1](phase-1.md), [Phase 2](phase-2.md),
[Phase 3](phase-3.md), [Phase 4](phase-4.md), [Phase 5](phase-5.md) and
[Phase 6](phase-6.md) for the
implemented contracts.

Controllers will accept DTOs and call application services. Services will enforce
organization access and own transaction boundaries. Inventory will own every
balance change; restocking will use inventory services. Modules should not bypass
one another through repositories. JPA entities are not API responses.
Package layout and code conventions are recorded in
[ADR-0001](adr/0001-backend-package-structure.md). `ModuleStructureTests`
enforces the boundaries with Spring Modulith: each top-level package is a
module, there are no cycles between modules, and sub-packages are internal
unless declared as a `@NamedInterface`. `ArchitectureTests` keeps HTTP types
(`org.springframework.http`, `.web`, `jakarta.servlet`) in controllers and the
web layer; services throw `shared.error` exceptions. The same test writes module diagrams to
`target/spring-modulith-docs`.

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

## Browse, photos and sales (Phase 6)

- **Location trail:** `GET /locations/{id}` returns `ancestors` from one
  recursive query that stays inside the organization at every step (depth
  capped at 32).
- **Counts:** `GET /locations` returns `childCount` and `itemCount`, using
  grouped queries per page. Item counts come from inventory through
  `LocationContentsCounter`, which `locations` defines and inventory
  implements, so `locations` still never imports `inventory`.
- **Sales:** `SALE` is a third removal type beside usage and waste. It uses
  the same locks, idempotency and reversal path (`V9`).
- **Item photos** live in PostgreSQL (`item_photos`, `V10`) rather than object
  storage. This keeps one container and one database, which suits the
  $0–5 host. The browser resizes before upload; the server checks the JPEG
  header dimensions before decoding. Lists read only photo versions, never
  bytes. Image URLs carry the version, so they can be cached privately.

## CSV import (Phase 7)

- **Writes only through services.** `imports` writes through `LocationService`,
  `CatalogService` and `StockService`, never through their repositories, so
  manual entry and import share every validation, lock and audit event. It
  reads through `ItemQueries`, `LocationQueries.tree` and
  `LocationContentsCounter`.
- **One planner for preview and apply.** `ImportPlanner` is pure: preview and
  apply run the same plan, and apply re-plans against current data in its own
  transaction after locking the organization.
- **Idempotent.** `import_batches` (`V11`) makes a retried import replay its
  first result. Starting-quantity operation keys are derived from the import
  key and the line, so the stock ledger is idempotent too.

### Deferred: equipment and assets

Individual objects (a particular espresso machine, a brew scale) are not
interchangeable stock. A future `assets` package would own:

- **`assets`:** identifier, name, condition, current location, notes, archived;
- **`asset_events`:** moved, condition changed, issue reported, checked out or
  returned, with actor and time, insert-only like the stock ledger;
- **labels:** an `ASSET` target type in `resource_codes`, so QR labels and
  Scan resolve assets the same way they resolve items and locations.

Assets would never contribute to stock balances. Until the module exists the UI
shows no checkout, custody or equipment states; equipment can be tracked as an
Each item.

POS automation and equipment remain later product capabilities.

See ../backroom_prd_v1.md for the approved product requirements.
