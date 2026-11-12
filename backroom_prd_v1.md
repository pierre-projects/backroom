# Backroom — Product Requirements Document

**Version:** 1.0  
**Working tagline:** Everything behind the business.  
**Status:** MVP planning  
**Primary platform:** Mobile-friendly web application  
**Backend:** Java + Spring Boot  
**Database:** PostgreSQL  
**Architecture:** Modular monolith  
**Deployment goal:** One application and one database, with an initial demo target of approximately $0–$5/month.

## 1. Overview

Backroom is a lightweight operations platform that connects physical stock, storage spaces, and equipment to useful digital records.

Owners and staff should be able to answer:

- What do we have?
- Where is it?
- How much is recorded?
- What changed, and who recorded it?
- What needs replenishment or attention?

Its signature feature is **Backroom Scan**. Businesses generate printable labels for items and locations. Staff scan them with an ordinary phone to view information and perform authorized actions.

Backroom should accommodate different small-business workflows without requiring a complicated configuration system. Liquor-store inventory and café operations are primary reference examples, while the underlying product remains applicable to shops, venues, studios, nonprofits, and service businesses.

The first release does not depend on selecting one exclusive customer category. Product decisions should be informed by practical use, particularly the developer’s access to café operations and observations of retail inventory management.

**Core promise:**

> Scan something → understand it → take action → leave a trustworthy history.

A trustworthy history means Backroom accurately records submitted actions. Physical accuracy also depends on staff participation, periodic counting, and, eventually, integrations that reduce manual recording.

## 2. Project goals and constraints

Backroom has two equally important goals:

1. Build an application that small businesses could realistically adopt.
2. Develop substantial hands-on experience with Java Spring Boot and enterprise backend engineering.

The second goal should be achieved through reliable business behavior, maintainable architecture, and meaningful verification.

### Non-negotiable technical requirements

- Java Spring Boot is the backend framework.
- PostgreSQL is the preferred database.
- The backend demonstrates dependency injection, Spring Security, Spring Data JPA, transaction management, validation, testing, and REST API design.
- Prefer a modular monolith.
- Avoid infrastructure and external services without a clear product or operational benefit.
- Keep the initial demo inexpensive.
- Do not require paid scanning, label-generation, or product-lookup APIs.

### Product principles

- **Scan first:** Labels provide a fast path to relevant information.
- **Low recording effort:** Routine updates should require little typing and few decisions.
- **Useful without perfect data:** Show recorded quantities and their history clearly; provide practical ways to reconcile discrepancies.
- **Simple for staff:** Present the actions relevant to their role and current context.
- **Accountable:** Important changes have an actor, timestamp, reason, and durable history.
- **Secure:** Organization boundaries and permissions are enforced by the backend.
- **Progressive automation:** Manual workflows work independently; later integrations reduce repetitive entry.

## 3. Users and reference workflows

| User | Main needs |
|---|---|
| Owner | Set up the business, manage access, understand stock, review activity, export data |
| Manager | Organize locations, receive and transfer stock, reconcile counts, review requests |
| Employee | Find items, record permitted usage or waste, request replenishment |
| Contractor — later | Access specific assets or tasks for a limited period |

### Liquor-store reference

Relevant workflows include:

- Register packaged products and existing manufacturer barcodes.
- Record deliveries.
- Find backstock and shelf locations.
- Transfer bottles or packages between storage and sales-floor locations.
- Count stock and investigate discrepancies.
- Eventually reconcile sales and returns from a POS integration.

The MVP does not promise automatic stock deductions from sales.

### Café reference

Relevant workflows include:

- Track cups, packaging, cleaning supplies, unopened ingredients, and other stock.
- Record deliveries and movements between storage areas.
- Record usage in practical aggregate quantities.
- Report waste and reconcile periodic counts.
- Request replenishment before supplies run out.

The MVP must not require employees to record every drink or ingredient use.

For example, a café may track unopened bags of beans and record bags leaving storage. Another business may choose to track an ingredient by weight. Each item has an explicit unit and recording approach.

### Recording effort

Backroom should support:

- Recording several units in one action.
- Logging aggregate usage after a task or shift.
- Counting an item when individual consumption is impractical.
- Prefilling location from a shelf or bin scan.
- Reusing known item and location information.

Do not assume scanning alone solves staff participation. Observe where recording is skipped and whether automation would meaningfully help.

## 4. Product scope

| Capability | Initial scope |
|---|---|
| Organizations and staff | Accounts, invitations, memberships, fixed roles |
| Inventory | Items, placements, balances, receipts, usage, waste, corrections |
| Locations | Nested rooms, shelves, cabinets, and bins |
| Backroom Scan | QR labels, existing product-barcode lookup, phone scanning, manual lookup |
| Transfers | Immediate movement between locations within one organization |
| Reconciliation | Manager-controlled count and correction for one item/location |
| Restock requests | Single-item requests, review, partial receiving, closure |
| Activity | Stock movement history and important administrative events |
| Search and low-stock visibility | Basic item search, filters, and low-stock list |
| Data portability | Basic CSV export |
| Equipment | Early enhancement after the inventory workflow is complete |
| Batch shelf audits | Later release |
| POS and other automated recording | Later release |

Equipment remains part of the product direction. It should not delay completion of the core inventory workflows.

## 5. Resource and inventory concepts

| Concept | Definition |
|---|---|
| Organization | A business and its access boundary |
| Item | A type of stock, such as a particular product, supply, or ingredient |
| Location | A physical storage address |
| Item placement | An item expected at a location, including when recorded stock is zero |
| Stock balance | Recorded quantity of one item at one location |
| Stock operation | One business action, potentially producing several movements |
| Stock movement | An immutable signed quantity change |
| Asset | An individually identified object, such as a particular refrigerator or microphone |
| Resource code | A stable identifier that resolves to a resource |
| Restock request | An internal request to replenish a particular item at a location |
| Receipt | A record of stock physically received |

### Location rules

- Locations form a hierarchy within an organization.
- Cycles are prohibited.
- A parent and child must belong to the same organization.
- The MVP stores quantities at leaf locations.
- Parent locations show totals aggregated from descendants.
- A zero balance does not remove the item’s expected placement.
- A location containing stock cannot be archived until that stock is transferred or reconciled.

Location hierarchy changes must not silently change stock quantities.

### Item and asset separation

An item represents interchangeable stock. An asset represents an individual object.

A manufacturer barcode generally identifies a product type. It does not identify a particular bottle, storage position, or piece of equipment.

Assets must not also contribute to stock balances unless a future workflow explicitly models that relationship.

## 6. Backroom Scan and labels

### Scan targets

**Item**

- Name, SKU, unit, recorded stock, and storage locations.
- Receive stock, record permitted usage or waste, and request replenishment.
- View operational movement history.

**Location**

- Name, parent, child locations, expected items, and balances.
- Open an item with the location already selected.
- Start a permitted transfer or single-item count.

**Asset — later**

- Identifier, condition, current location, and notes.
- Issue reporting and, subsequently, checkout and maintenance history.

### Code strategy

Generated QR codes are the default internal label format for items and locations.

| Identifier | Purpose |
|---|---|
| Generated QR URL | Opens a stable Backroom resource destination |
| Existing UPC/EAN | Looks up a registered item within the selected organization |
| Human-readable code | Manual fallback when scanning is unavailable |
| Generated Code 128 — later | Internal barcode labels when validated business needs justify them |

Requirements:

- QR codes contain stable opaque identifiers, not quantities, names, or assignments.
- Generated QR tokens are globally unique.
- Manufacturer codes are unique within their organization and identifier namespace.
- Multiple codes may identify the same resource.
- Identifier namespace and printed barcode format are distinct concepts.
- Preserve leading zeros and normalize equivalent UPC/EAN representations consistently.
- A code cannot silently be reassigned to another resource.
- Codes provide identification, never authorization.
- No paid product database is required.

Scanning an unknown product barcode returns an organization-specific “not registered” state. Authorized users can register it; employees cannot silently create or rebind items.

### Phone scanning

Support both:

1. Opening a QR URL using the phone’s ordinary camera.
2. Scanning QR or product barcodes inside Backroom.

The app must:

- Use HTTPS for deployed camera access.
- Preserve the intended destination through sign-in.
- Check authorization before returning private resource details.
- Ask users to select an organization when a product barcode is ambiguous across their memberships.
- Pause scanning after successful detection.
- Require an explicit action and confirmation before stock changes.
- Handle camera denial, unreadable labels, and network failures.
- Offer manual lookup and item search.
- Treat unfamiliar scanned URLs as unsupported input rather than automatically opening them.

Browser-native barcode detection may be used when available, with a bundled fallback. Target-phone testing is required before selecting the final scanning library.

### Label generation and printing

Owners and managers can:

1. Select a resource.
2. Preview its code, name, and human-readable identifier.
3. Download an individual label or a simple printable sheet.
4. Reprint an existing label.

Initial printing supports a small number of tested layouts for A4 and US Letter.

Requirements:

- Adequate contrast and quiet zones.
- Explicit actual-size printing instructions.
- A calibration print.
- Readable text when scanning fails.
- Bounded batch sizes.
- Testing with ordinary printers and physical labels.

PDFs are generated on demand and do not require persistent file storage.

Damaged labels can be reprinted without changing the resource. Revoked codes remain revoked, and retired identifiers are not recycled. Duplicate item labels are allowed and do not represent additional stock.

Choose a stable hostname before substantial physical deployment.

## 7. Inventory rules and workflows

### Quantity rules

- Each item has one explicit base unit.
- Count-based units require whole quantities.
- Measured units use documented decimal precision.
- Use Java `BigDecimal` and PostgreSQL `NUMERIC`.
- Do not use floating-point quantities.
- Automatic case, package, recipe, and unit conversion are outside the MVP.
- The meaning of an item’s base unit cannot change after movements exist.
- Negative stock is prohibited in the MVP.

A business may initially track a case as its own item or track its contents in a base unit. The interface must make that choice explicit.

### Opening stock

Starting quantities create opening-balance movements with an actor and timestamp. They must not bypass movement history.

### Receive stock

1. Select the item and destination location.
2. Enter the quantity actually received.
3. Add a reference or note where useful.
4. Confirm.
5. Save the balance update, movement, and audit information atomically.

### Record usage or waste

1. Scan or find the item.
2. Confirm the source location.
3. Enter a positive quantity and action type.
4. Confirm.
5. Validate permission and availability, then commit atomically.

Usage may represent several physical consumptions recorded together. The application should not imply that every sale or serving has been individually recorded.

### Transfer stock

1. Select an item, source, destination, and quantity.
2. Validate that both locations belong to the organization.
3. Reject identical source and destination locations.
4. Verify available stock.
5. Commit outgoing and incoming movements under one operation.

A transfer preserves the organization’s total quantity for that item. One side cannot succeed independently.

### Count and correct

1. A manager opens an item at a location.
2. Backroom displays the recorded quantity and its version.
3. The manager enters the physically observed quantity.
4. Backroom checks for intervening stock changes.
5. If unchanged, save the observation and any correction movement.
6. If changed, require review or recount.

A matching count can be recorded without creating a zero-quantity movement.

This is a single-item workflow. Batch counts, discrepancy queues, and separate count approval are later features.

### Mistakes and history

- Committed movements are immutable.
- Corrections use linked compensating operations.
- Request-linked receipt corrections must keep received quantities and request state consistent.
- Referenced items, locations, and users are archived or deactivated rather than deleting history.
- Stock balances cannot be edited through a generic CRUD endpoint.

## 8. Restock requests and receiving

The MVP models internal replenishment coordination. Approval does not mean a supplier order was placed.

### Request contents

Each request initially contains:

- One item.
- A destination location.
- Requested quantity in the item’s base unit.
- Requester.
- Optional note.
- Review, receipt, and closure history.

Multi-item requests and supplier purchasing can follow later.

### Workflow

1. An employee requests replenishment.
2. Backroom shows existing outstanding requests for the item/location.
3. A manager approves or rejects the request.
4. An authorized user records each actual delivery.
5. Stock increases by the received quantity.
6. The request remains open until fully received or explicitly closed short.

Suggested states:

- Submitted
- Approved
- Partially received
- Fulfilled
- Rejected
- Cancelled
- Closed short

### Rules

- Approval never changes stock.
- Receipt before approval is prohibited.
- Receipt quantities must be positive.
- Partial deliveries are supported.
- Over-receipt against a request is rejected; additional stock requires a separate authorized receipt.
- Closing a remainder requires a reason.
- Received history remains visible after closure.
- Approved quantities cannot be silently edited.
- Repeated receipt submissions cannot double-count stock.
- Receipt, movement, balance, and request-state updates commit together.

## 9. Roles, authorization, and tenant isolation

Use fixed roles initially.

| Action | Owner | Manager | Employee |
|---|:---:|:---:|:---:|
| Manage organization and ownership | Yes | No | No |
| Invite, remove, or change staff roles | Yes | No | No |
| Create items, locations, and labels | Yes | Yes | No |
| Scan and view operational resources | Yes | Yes | Yes |
| Record routine usage and waste | Yes | Yes | Yes |
| Receive, transfer, count, or correct stock | Yes | Yes | No |
| Submit restock requests | Yes | Yes | Yes |
| Approve, reject, or close requests | Yes | Yes | No |
| View organization-wide administrative activity | Yes | Yes | No |
| View relevant item movement history | Yes | Yes | Yes |

Billing is outside the initial product scope.

### Access requirements

- One user may belong to multiple organizations.
- Roles belong to memberships, not globally to users.
- Relevant reads and writes verify current membership.
- The API derives the actor from authentication.
- Client-supplied actor IDs or role claims are not trusted.
- The last owner cannot be removed or demoted.
- Removing a membership prevents subsequent authorized access.
- Employee movement-history access does not expose administrative security events.
- Invitations expire and are single-use.
- Account recovery must be established before business reliance.

### Tenant isolation

- Organization-owned records carry an organization identifier.
- Queries and commands are organization-scoped.
- Relationships between tenant-owned records use same-tenant database constraints.
- QR lookup enforces membership before revealing private details.
- Labels, exports, searches, and nested references receive the same isolation guarantees as ordinary endpoints.
- Inaccessible foreign-tenant resources receive a consistent response without revealing their existence.

## 10. Recommended data model

| Area | Principal records |
|---|---|
| Access | `users`, `organizations`, `memberships`, `invitations` |
| Storage | `locations` |
| Catalog | `items`, `item_placements` |
| Inventory | `stock_balances`, `stock_operations`, `stock_movements`, `stock_counts` |
| Replenishment | `restock_requests`, `receipts` |
| Identification | `resource_codes` |
| Accountability | `audit_events` |
| Equipment — later | `assets`, `asset_issues` |

Important constraints:

- Unique membership per organization/user.
- Unique item placement per organization/item/location.
- One balance per placement.
- Nonnegative balances.
- Organization-scoped SKU uniqueness when supplied.
- Same-tenant foreign keys for locations, items, placements, requests, and receipts.
- Globally unique generated QR tokens.
- Organization-scoped normalized manufacturer codes.
- Idempotency-key uniqueness within the organization.
- Exactly one typed resource target per code record.

Use explicit item and location references in `resource_codes`; add asset references when equipment ships. Avoid an unconstrained polymorphic `resource_id` for operational code lookup.

Generic resource references are acceptable in historical audit metadata where retaining an event after archival is intentional.

Additional requirements:

- Store timestamps with time-zone-aware database types.
- Use explicit indexes for tenant-scoped lookup and history pagination.
- Preserve historical actor attribution after membership deactivation.
- Avoid cascading deletes into movement or audit history.
- Store enough operation information to identify retries and explain corrections.

## 11. Technical architecture

### Backend

- Java 21.
- Supported Spring Boot GA release.
- Maven.
- Spring MVC.
- Spring Security.
- Spring Data JPA / Hibernate.
- PostgreSQL.
- Bean Validation.
- Flyway.
- JUnit Jupiter and Testcontainers.
- OpenAPI documentation.

Use Boot-managed dependency versions where appropriate.

### Frontend

Use React with Vite, built as static assets and served by Spring Boot.

This keeps the initial deployment to one application process and one origin. A separate frontend runtime is unnecessary for the initial requirements.

### Module structure

```text
com.backroom
├── access
├── locations
├── inventory
├── restocking
├── labels
├── audit
├── assets       # added when equipment ships
└── shared
```

Each module owns its API handling, application services, business logic, and persistence.

Rules:

- Use constructor injection.
- Controllers accept validated DTOs.
- Application services authorize commands and define transaction boundaries.
- Inventory owns all balance changes.
- Restocking calls inventory services.
- Labels use resource-owner query interfaces.
- Avoid cross-module repository access.
- Do not serialize JPA entities directly.
- Disable Open Session in View.
- Keep shared code small and technical.
- Avoid generic CRUD abstractions that obscure business rules.

### Authentication

Use Spring Security session authentication with:

- Secure, HttpOnly cookies.
- CSRF protection.
- Spring-supported password encoding.
- Explicit API authentication and authorization responses.

In-memory sessions are acceptable for a single-instance demo if restarts require sign-in. JDBC-backed Spring Session may be added when persistent sessions become useful.

Custom JWT infrastructure and Redis are not initial requirements.

### Labels

- ZXing for Java code generation.
- PDFBox for printable PDFs.
- Bundled browser scanning with tested fallback behavior.
- No required paid scanning service or runtime CDN dependency.

## 12. Transaction and API requirements

### Stock transaction strategy

Use short application-service transactions and pessimistic locking for balance mutations.

For each stock command:

1. Verify membership, permission, and resource ownership.
2. Claim the idempotency key.
3. Lock related request records when applicable.
4. Lock balances in a consistent order.
5. Validate stock and workflow state.
6. Update balances and append movement, receipt, and audit records.
7. Commit together.

Missing destination balances must be created safely under unique constraints before locking.

Use `@Version` for stale-edit and count detection. Do not assume a version field alone prevents invalid stock operations.

PDF generation and external network work remain outside stock transactions.

### Idempotency

Receipt, usage, waste, transfer, and correction commands accept an operation identifier.

- Retries reuse the same identifier.
- A successful command produces one business effect.
- Reusing a key with a different actor or payload is rejected.
- Concurrent duplicates are controlled by database uniqueness.
- Duplicate-key handling must respect transaction rollback behavior.
- UI button disabling is helpful but does not replace backend guarantees.

### API direction

Prefer business commands over arbitrary balance edits.

```text
POST /api/v1/organizations
POST /api/v1/organizations/{orgId}/invitations

GET  /api/v1/organizations/{orgId}/items
POST /api/v1/organizations/{orgId}/items
POST /api/v1/organizations/{orgId}/locations

POST /api/v1/organizations/{orgId}/inventory/receipts
POST /api/v1/organizations/{orgId}/inventory/removals
POST /api/v1/organizations/{orgId}/inventory/transfers
POST /api/v1/organizations/{orgId}/inventory/counts

POST /api/v1/organizations/{orgId}/requests
POST /api/v1/organizations/{orgId}/requests/{id}/approve
POST /api/v1/organizations/{orgId}/requests/{id}/reject
POST /api/v1/organizations/{orgId}/requests/{id}/receipts
POST /api/v1/organizations/{orgId}/requests/{id}/close-short

POST /api/v1/organizations/{orgId}/scan-resolutions
GET  /api/v1/organizations/{orgId}/labels.pdf
GET  /api/v1/organizations/{orgId}/audit-events
```

The public-facing `/s/{token}` destination opens the application and preserves the intended resource through authentication.

List endpoints require pagination. Commands return consistent responses and RFC 9457 problem details for errors.

## 13. Engineering quality and definition of done

### Required practices

- Flyway owns schema changes.
- Hibernate validates the schema.
- Bean Validation handles request shape; services enforce business rules.
- Consistent error responses distinguish validation, permission, stock, and state conflicts.
- Structured logs include useful operation and correlation identifiers.
- Secrets, credentials, and session tokens are excluded from logs.
- Durable business audit entries participate in the business transaction.
- Actuator provides appropriately protected health and operational information.
- CI runs tests and builds the application and container.

### Critical verification scenarios

| Scenario | Expected behavior |
|---|---|
| Concurrent removals exceed available stock | Only available stock can be removed; no negative balance |
| Concurrent retries of one receipt | One receipt and one stock increase |
| Idempotency key reused with different contents | Conflict with no additional effect |
| Failure during stock operation | Balance, movements, receipt/request changes, and audit roll back |
| Transfer fails midway | Neither side commits |
| Opposing transfers | Quantities remain correct; locking is controlled |
| Count overlaps another movement | Stale count is rejected |
| Invalid request transition | No stock or workflow mutation |
| Cross-organization IDs or references | No disclosure or mutation |
| Resource renamed or moved | Existing labels continue resolving |
| Resource archived or code revoked | Defined inactive state; no silent reassignment |
| Membership removed | Subsequent access is denied |
| Fresh database migration | Application starts with the expected schema |

Concurrency tests run against PostgreSQL with separate transactions and connections. Tests must coordinate competing operations rather than relying on incidental timing.

Use focused unit tests for quantity rules and state transitions, integration tests for persistence and authorization, and a small browser suite for primary workflows.

Physical phone and printer verification is part of scan readiness.

### Definition of done for critical operations

- PostgreSQL integration tests pass.
- Relevant concurrency and retry tests pass.
- Tenant isolation is verified.
- Errors are consistent and actionable.
- Business history explains the committed result.
- No external infrastructure is required merely to make the operation reliable.

## 14. MVP experience and prioritized requirements

### Owner onboarding

1. Create an account and organization.
2. Add a storage location.
3. Add an item and its unit.
4. Record starting stock.
5. Print a label.
6. Scan it.
7. Invite an employee.

The owner should reach the first useful scan before configuring optional settings.

### Main navigation

- Home — low stock and pending requests.
- Inventory — search, items, balances, and history.
- Locations — storage hierarchy and contents.
- Scan — prominent mobile camera action.
- Requests — submission, review, and receiving.
- More — people, activity, exports, and settings.

### Required MVP

- Authentication, organizations, invitations, and roles.
- Locations, items, placements, and opening stock.
- Receipts, usage, waste, transfers, and single-item reconciliation.
- Movement history and durable activity.
- QR generation and printing.
- Existing UPC/EAN registration and lookup.
- Phone scanning, search, and manual entry.
- Low-stock indicators.
- Single-item requests with partial receiving and explicit closure.
- Basic CSV export.
- Mobile-friendly screens and useful empty/error states.

### Early improvements after core workflows

- Template-based CSV import with validation and duplicate review.
- Faster bulk entry.
- Basic equipment records and QR labels.
- Generated Code 128 labels.
- Additional label layouts.

CSV import should move earlier if catalog setup becomes a major adoption burden, particularly for retail businesses with many products.

### Outside the MVP

- POS integrations and sales-driven deductions.
- Payment processing or a full checkout system.
- Recipe and package conversion.
- Lots, expiry dates, and serialized stock.
- Offline synchronization.
- Supplier ordering and payments.
- Equipment checkout and maintenance scheduling.
- Batch shelf-audit approval.
- Custom permission builders and workflow designers.
- Real-time collaboration infrastructure.
- AI forecasting.

## 15. Phased implementation roadmap

| Phase | Product milestone | Enterprise Java experience |
|---|---|---|
| **1. Foundation** | Authentication, two isolated organizations, locations, items, migrations, and a thin deployed application | Boot configuration, constructor DI, Security, JPA relationships, validation, REST, CI |
| **2. Inventory correctness** | Opening stock, receipt, usage, waste, transfer, count correction, and movement history | Transactions, locking, optimistic version checks, constraints, idempotency, PostgreSQL integration tests |
| **3. Physical scanning** | Print a label, scan it on target phones, authenticate if necessary, and reach the correct resource | Module boundaries, binary responses, Java libraries, API integration, authorization |
| **4. Replenishment** | Request, review, partial receipt, fulfillment, and closure work end to end | State transitions, cross-module orchestration, concurrency testing, transactional consistency |
| **5. Usability and operations** | Search, low stock, exports, account recovery, deployment recovery, and practical business feedback | Observability, configuration management, migration discipline, backup restoration |
| **6. Product expansion** | Add equipment, import improvements, or automation according to observed needs | Integration design and domain extension without breaking core invariants |

Testing and documentation accompany each phase. Deployment begins early rather than waiting until the application is otherwise finished.

Maintain architecture notes explaining tenant isolation, stock concurrency, idempotency, and important tradeoffs.

## 16. Deployment and operating cost

### Initial topology

- One containerized Spring Boot application serving the frontend and API.
- One PostgreSQL database.
- Generated PDFs returned on demand.
- No required object storage, message broker, Redis, or separate worker service.

### Cost requirements

- Target approximately $0–$5/month for the initial demo.
- Verify current provider allowances before deployment.
- Do not treat temporary credits or expiring databases as permanent hosting.
- Accept sleeping services for a clearly identified portfolio demo.
- Establish a separate budget before depending on the application for daily business operations.

### Memory and operational requirements

- Measure container memory during startup, ordinary requests, concurrency tests, and PDF generation.
- Leave memory for the JVM beyond the Java heap.
- Use a small connection pool.
- Bound label batches and expensive requests.
- Build application images in CI.
- Avoid unnecessary polling that consumes free-tier database compute.
- Do not rely on local container files for durable data.

Before real business reliance, provide:

- Reliable database backups.
- A tested restore procedure.
- Basic monitoring and failure visibility.
- A deployment process that preserves data.
- Clear recovery behavior after application restarts.
- An availability level appropriate to the business.

## 17. Future POS and automated recording

Reducing manual recording is a deliberate product direction.

Potential sources include:

- POS sales and returns.
- Inventory or purchasing exports.
- Supplier delivery data.
- Scheduled imports.
- A lightweight companion sales-entry workflow if existing integrations do not meet practical needs.

Prefer integrating with an existing system before committing to building a complete POS product.

### Future capabilities

- Map external products to Backroom items.
- Import transactions through supported APIs, webhooks, or files.
- Convert sales into appropriate stock operations.
- Reconcile automated activity with counts and manual adjustments.
- Show sync status, failures, and unresolved mappings.

### Required safeguards

Future integration must account for:

- Duplicate and retried events.
- Returns, voids, refunds, and corrections.
- Delayed or out-of-order records.
- Missing product mappings.
- Packaging differences.
- Ingredient and recipe conversion.
- Waste and consumption unrelated to sales.
- Location assignment.
- Conflicts between automated deductions and manual aggregate entries.

A café sale is not automatically a precise measurement of ingredient consumption. Recipe-based deductions, if added, are modeled estimates that still require reconciliation.

Automated activity must use the same inventory services, tenant checks, movement history, and transactional guarantees as manual commands.

Prepare the core to accept operations with a source and external reference. Do not build a generic integration platform before a concrete integration is selected.

## 18. Success measures and open decisions

### Product success

- An owner can create an item and location, print a label, and scan it without extensive setup.
- Staff can find stock and complete common actions without training.
- Recording effort is acceptable during actual work.
- Transfers and counts allow records to recover from ordinary discrepancies.
- Requests progress from submission to actual receipt.
- Businesses can export their data.
- Users continue using the product after initial onboarding.

### Technical success

- Concurrent operations preserve stock invariants.
- Retries do not double-count.
- Tenant boundaries hold across all supported access paths.
- Recorded actions have durable, attributable history.
- Deployment and recovery procedures are reproducible.
- The demo remains within its measured hosting budget.

### Questions to validate during development

- Which resources are most useful to label in café and retail settings?
- Do staff prefer item-first or location-first scanning?
- Which stock changes can reasonably be recorded immediately?
- Where do aggregate usage and periodic counts work better?
- How much catalog-import support is needed?
- Are employee receipt or transfer permissions needed earlier?
- Which existing POS systems or exports are actually available?
- Would POS integration remove substantial work, or would another recording aid help more?
- Does equipment tracking provide enough value to prioritize next?
- What availability and recovery expectations justify a paid deployment?

These questions guide iteration. They do not require choosing one exclusive business category before building the shared foundation.



