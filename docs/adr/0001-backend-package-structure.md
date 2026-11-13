# ADR-0001: Backend package structure and team conventions

**Status:** Accepted (all action items done on `refactor/backend-conventions`)
**Date:** 2026-11-13
**Deciders:** Backend maintainers

## Context

The Spring Boot backend (`src/main/java/com/backroom`, ~5,150 lines, 98 files) is
organised **by feature**: each top-level package (`access`, `inventory`,
`locations`, `labels`, `restocking`, `search`, `imports`, `exports`, `audit`) holds
its own controllers, services, repositories, entities and DTOs, with `shared` for
cross-cutting pieces. `docs/architecture.md` already describes this as module
ownership with a "no reaching into another module's repositories" rule.

The question raised: *is it industry standard to keep controllers and services
together, and how should the project look for a team working in Spring Boot?*

### Audit summary

**What is already team-grade (keep it):**

| Area | Evidence |
| --- | --- |
| Package-by-feature | One package per business capability, matching the example in the Spring Boot reference docs ("Structuring Your Code") and the Spring Modulith model |
| Encapsulation | Controllers, repositories and entities are package-private; only services, DTOs and extension interfaces are `public` |
| Thin controllers | No controller touches a repository; each delegates to a service |
| Dependency inversion across modules | `StockReversalListener`, `LocationContentsCounter`/`PlacementContentsCounter` let modules react without circular imports |
| Transactions at the service layer | `@Transactional` on services only, `readOnly = true` on queries |
| Constructor injection | No field `@Autowired` in production code |
| Schema | Flyway versioned migrations; JPA entities never returned from controllers |
| Tests and CI | Testcontainers PostgreSQL, a container smoke test, a backup/restore drill and browser e2e in CI |

**What a Spring team would flag:**

| # | Finding | Where | Severity |
| --- | --- | --- | --- |
| F1 | Several top-level types per file (marker classes holding entities/repositories) | `StockRecords` (4 entities), `StockRepositories` (4 repos), `RestockRecords` (4), `LabelsController` (+`CodeLinkController`), `StockCommandHooks` (+impl), `PasswordReset`, `ImportBatch`, `ItemPhoto` | High: breaks "one public type per file", hides classes from IDE file search and code review |
| F2 | No enforced formatter; dense, inconsistent style | One-line constructors (`this.a=a; this.b=b;`), `precision=15` vs `readOnly = true`, wildcard imports in 38 files, fully-qualified `java.time.Instant` next to an import in `AccessDtos` | High: every contributor reformats differently, noisy diffs |
| F3 | Module boundaries are documented but not enforced | Nothing fails the build if `search` starts using `inventory` repositories | Medium |
| F4 | `shared` depends on `access` (a cycle) | `shared/security/SecurityConfiguration` imports `com.backroom.access.*` | Medium |
| F5 | Integration tests are named after delivery phases, all in the root package | `FoundationIntegrationTests`, `UsabilityIntegrationTests`, `OperationsIntegrationTests` | Medium: names stop meaning anything once the phases are history; hard to find the tests for a module |
| F6 | Entities expose package-private fields that services mutate directly | `item.unit`, `membership.active`, `StockBalance.quantity` | Medium: unconventional; field access bypasses Hibernate lazy proxies if `getReferenceById` or associations are ever introduced |
| F7 | Services throw exceptions carrying `HttpStatus` | `StockService`, `ImportService`, `ExportService`, `CodeService`, `ItemPhotoService`, `ImportPlanner`, `AccessPolicy` import `org.springframework.http.HttpStatus` | Low: pragmatic, but couples business logic to HTTP |
| F8 | `inventory` is becoming two modules | 25 files mixing catalog (items, photos, placements) and stock (balances, operations, movements, counts) | Low today, grows with features |
| F9 | `shared` is a grab-bag | Persistence base classes, web error handling, request-id filter, SPA forwarding and production settings side by side | Low |

## Decision

**Keep package-by-feature.** Do not move to `controller/`, `service/`,
`repository/` layer packages. Tighten conventions inside each feature package
instead, and let the build enforce them.

Target layout:

```
com.backroom
├── BackroomApplication.java
├── access/                      # module: auth, organizations, memberships, invitations
│   ├── AuthController.java          (package-private)
│   ├── OrganizationController.java
│   ├── AccountService.java          (public API of the module)
│   ├── AccessPolicy.java
│   ├── Membership.java              (package-private entity, private fields)
│   ├── MembershipRepository.java    (package-private)
│   ├── AccessDtos.java              (public records)
│   └── security/
│       └── SecurityConfiguration.java   ← moved from shared/security
├── inventory/                   # stock: balances, operations, movements, counts
│   ├── StockController.java
│   ├── StockCommands.java
│   ├── StockService.java
│   ├── StockBalance.java            ← one file per entity
│   ├── StockOperation.java
│   ├── StockMovement.java
│   ├── StockCount.java
│   ├── StockBalanceRepository.java  ← one file per repository
│   └── ...
├── catalog/                     # optional later split (F8): items, photos, placements
├── locations/ labels/ restocking/ search/ imports/ exports/ audit/
└── shared/
    ├── error/         DomainException and its subclasses (NotFound, Conflict, InvalidRequest, ...)
    ├── persistence/   RecordEntity, TenantRecord
    ├── web/           ApiExceptionHandler, ProblemResponses, PageResponse, RequestIdFilter, SpaController
    └── config/        ApplicationConfiguration, ProductionSettings

src/test/java/com/backroom
├── ModuleStructureTests.java        ← Spring Modulith verify()
├── InfrastructureIntegrationTests.java, DatabaseMigrationIntegrationTests.java
├── access/      AuthIntegrationTests, OrganizationIntegrationTests, InvitationIntegrationTests,
│                PasswordResetIntegrationTests
├── inventory/   StockIntegrationTests, CatalogIntegrationTests, LowStockIntegrationTests,
│                SaleIntegrationTests, QuantitiesTests
├── locations/   LocationHierarchyIntegrationTests, LocationContentsIntegrationTests
├── labels/ restocking/ imports/ exports/ search/ ...
├── shared/web/  RequestIdIntegrationTests, SpaRoutingIntegrationTests, RequestIdFilterTests
└── support/     @IntegrationTest, ApiTestSupport, PostgresTestConfiguration, TestClockConfiguration
```

Conventions:

1. One top-level type per file.
2. A formatter runs in the build (Spotless with palantir-java-format or
   google-java-format) and `spotless:check` fails CI. No wildcard imports.
3. Within a module: controllers, repositories and entities stay package-private;
   the module's public surface is its services, DTO records and extension
   interfaces.
4. Module dependencies are verified by a test (Spring Modulith
   `ApplicationModules.of(BackroomApplication.class).verify()`, or ArchUnit if
   Modulith is not wanted). `shared` depends on no feature module.
5. Tests live in the package of the module they cover and are named after the
   behaviour, not the delivery phase. Database-backed tests use
   `@IntegrationTest` and extend `support.ApiTestSupport`; a class that adds
   beans, spies or imports gets its own Spring context, so add them only when
   the test needs them.
6. Entities keep fields `private`, expose package-private getters only for
   what other classes read, and change state through intention-revealing
   methods (`item.archive()`, `request.approve(...)`). Guards that need
   repositories or produce API error codes stay in services.
   `ArchitectureTests` fails the build on a non-private `@Entity` field.
7. Services throw `shared.error` exceptions (`NotFoundException`,
   `ConflictException(code, message)`, `InvalidRequestException`,
   `ForbiddenException`, ...); `ApiExceptionHandler` alone maps them to HTTP.
   `ArchitectureTests` fails the build if code outside controllers, `shared.web`
   and `access.security` uses `org.springframework.http`, `.web` or
   `jakarta.servlet` types.

## Options considered

### Option A: Package-by-feature, tightened (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low: mostly file splits, a formatter and one test |
| Cost | ~1–2 days, mostly mechanical |
| Scalability | Good: modules can be split out or given owners independently |
| Team familiarity | High with current Spring guidance (Boot docs, Spring Modulith) |

**Pros:** keeps the existing encapsulation (package-private repositories are
only possible in this layout); a feature change touches one folder; boundaries
become build-checked.
**Cons:** some developers trained on tutorials expect layer folders and need
the convention explained (README / this ADR).

### Option B: Package-by-layer (`controller/`, `service/`, `repository/`, `entity/`, `dto/`)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: every type moves, most become `public` |
| Cost | ~2–3 days plus loss of encapsulation |
| Scalability | Poor: a 10-module app ends with 40+ classes per layer folder |
| Team familiarity | High in older enterprise codebases and tutorials |

**Pros:** instantly recognisable to anyone from a tutorial background.
**Cons:** repositories and entities must be `public` to cross packages, so any
module can bypass another's service: the exact rule `docs/architecture.md`
forbids. Feature changes scatter across five folders.

### Option C: Feature modules with layer sub-packages (`inventory/web`, `inventory/service`, `inventory/persistence`)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium |
| Cost | ~2 days |
| Scalability | Good |
| Team familiarity | High: common in larger corporate codebases |

**Pros:** familiar to both camps; tidy for very large modules.
**Cons:** Java has no sub-package visibility, so repositories and entities
again become `public`. Only worth it if paired with Spring Modulith (which treats
sub-packages as module-internal) and a module larger than ~30 files.

## Trade-off analysis

The deciding factor is visibility. Package-private repositories and entities
are what currently stop `search` or `imports` from writing to inventory tables
behind `StockService`'s back. Options B and C both force those types public and
then need tooling to claw the guarantee back. Option A keeps the compiler doing
that work and adds Spring Modulith as a second line of defence. Option C stays
available for a module that outgrows a flat package.

## Consequences

- Easier: onboarding (one-type-per-file, consistent formatting), reviewing diffs,
  finding a module's tests, catching boundary violations in CI.
- Harder: one large formatting commit touches every file; add it to
  `.git-blame-ignore-revs` so `git blame` stays useful.
- Revisit: splitting `inventory` into `catalog` + `inventory` once it passes ~30
  files; Option C for any module that gets its own team.

## Action items

1. [x] Add Spotless (palantir-java-format, `removeUnusedImports`,
       `forbidWildcardImports`) to `pom.xml`, bound to `verify` so CI enforces it;
       add `.editorconfig`; expand all wildcard imports (static included) and
       reformat every Java file (F2). After that commit lands, record its SHA in
       `.git-blame-ignore-revs`.
2. [x] Split multi-type files into one type per file; remove the empty
       `StockRecords`, `StockRepositories` and `RestockRecords` holders (F1).
3. [x] Move `SecurityConfiguration` to `access/security`; split `shared` into
       `persistence`, `web`, `config` (F4, F9). `shared` now imports no
       feature module; the `RequestIdFilter` tests moved to `shared/web`.
4. [x] Add Spring Modulith 2.1 and `ModuleStructureTests`, which runs
       `verify()` and writes module diagrams to `target/spring-modulith-docs`
       (F3). The only findings were the `shared` sub-packages; `shared.web`
       and `shared.persistence` are now declared `@NamedInterface`s, and
       `shared.config` stays internal. No cycles, and no feature module
       reached into another module's internals.
5. [x] Move and rename integration tests per module; move `ApiTestSupport` and
       the Postgres config into `support/` (F5). The nine phase-named classes
       became eighteen classes named after their behaviour, in the module they
       cover; the same 85 tests run, with bodies unchanged apart from record
       accessor calls. `@IntegrationTest` replaces the repeated
       `@SpringBootTest`/`@AutoConfigureMockMvc`/`@Import` trio, and the
       duplicate HTTP helpers that `FoundationIntegrationTests` kept were merged
       into `ApiTestSupport` (its stricter `register()` now runs for every test).
6. [x] Introduce domain exceptions and drop `HttpStatus` from services (F7).
       `ApiException` is replaced by `shared.error.DomainException` and seven
       subclasses, one per HTTP outcome (400, 401, 403, 404, 409, 413, 415);
       status, error code and message of every response are unchanged.
       `ArchitectureTests` (ArchUnit) keeps HTTP types in the web layer, and
       `ApiExceptionHandlerTests` pins each exception's status and code.
7. [x] Make entity fields private with getters and behaviour methods (F6).
       All 18 entities: ~45 field writes became behaviour methods (for example
       `RestockRequest.approve/reject/cancel/closeShort/recordDelivery`,
       `Membership.activate/changeRole/deactivate`, `ItemPhoto.replace`),
       `ImportBatch` is built complete through a `Counts` record, and 305
       reads became 89 getters. `ArchitectureTests.entityFieldsArePrivate`
       keeps it that way.
8. [x] Update `docs/architecture.md` to link this ADR and state the conventions.
