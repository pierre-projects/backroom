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
| audit | Durable business activity |
| assets | Equipment, when that capability ships |
| shared | Small technical configuration and primitives |

Phase 1 implements access, locations, catalog/placements within inventory, audit,
and shared technical configuration. Restocking, labels, and assets remain future
modules. See [Phase 1 defaults](phase-1.md) for the implemented contract.

Controllers will accept DTOs and call application services. Services will enforce
organization access and own transaction boundaries. Inventory will own every
balance change; restocking will use inventory services. Modules should not bypass
one another through repositories. JPA entities are not API responses.

## Decisions

- Spring Boot 4.1.2, Spring MVC, Security, JPA, Validation, Actuator, Flyway.
- Java 21 is enforced by Maven; Windows setup uses an ignored portable JDK.
- PostgreSQL 17 is used in Compose and Testcontainers.
- Flyway applies the access, catalog/locations, and audit migrations; Hibernate
  uses validate, never update or create.
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

## Phase 2 boundary

Add balances, immutable stock movements, idempotent commands, and stock concurrency
tests. Freeze unit meaning after movements exist and extend archival/placement
guards to prevent discarding locations with stock. POS automation and equipment
remain later product capabilities.

See ../backroom_prd_v1.md for the approved product requirements.
