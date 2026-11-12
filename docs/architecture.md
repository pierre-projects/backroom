# Phase 0 architecture

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

Only the application entry point and shared security configuration exist in
Phase 0. Do not create empty controllers, repositories, or invented domain tables.

Controllers will accept DTOs and call application services. Services will enforce
organization access and own transaction boundaries. Inventory will own every
balance change; restocking will use inventory services. Modules should not bypass
one another through repositories. JPA entities are not API responses.

## Decisions

- Spring Boot 4.1.2, Spring MVC, Security, JPA, Validation, Actuator, Flyway.
- Java 21 is enforced by Maven; Windows setup uses an ignored portable JDK.
- PostgreSQL 17 is used in Compose and Testcontainers.
- Flyway initializes migration history now. Phase 1 supplies the first business
  migration; Hibernate uses validate, never update or create.
- No default or demo account exists. Spring's generated user is disabled.
- GET /actuator/health returns status only. Administrative endpoints are unexposed
  and denied even if a future caller is authenticated.
- API routes require authentication. CSRF stays enabled. Login and account
  workflows begin in Phase 1.
- Sessions will use secure HttpOnly cookies behind production HTTPS. Local HTTP
  leaves the Secure flag off; set SESSION_COOKIE_SECURE=true for HTTPS deployment.
- The frontend checks connectivity once on mount or explicit retry; no polling.
- Runtime image runs as a non-root user. PostgreSQL data resides in a named volume.
- No Redis, message broker, object storage, external font, or paid API is needed.

## Phase 1 boundary

Implement accounts, organizations, membership authorization, and the first domain
migrations. Keep stock concurrency and idempotency tests with the inventory
milestone. POS automation and equipment remain later product capabilities.

See ../backroom_prd_v1.md for the approved product requirements.
