# Backroom

**Everything behind the business.**

A mobile-friendly operations platform for small businesses, built with Java
Spring Boot and PostgreSQL. Café operations and liquor-store inventory are
reference workflows. The product emphasizes physical labels, practical recording
effort, and trustworthy stock history.

Phase 0 provides a runnable foundation. Accounts, organizations, inventory actions,
and scanning arrive in later phases; the current landing page checks connectivity.

- [PRD v1](backroom_prd_v1.md)
- [Architecture and module boundaries](docs/architecture.md)

## Requirements

- Windows PowerShell, Git, Node.js 24, npm 11, and Docker Desktop using Linux containers.
- Java 21. Setup downloads a checksum-verified portable Temurin JDK into ignored
  `.tools/jdk-21`; it does not change the machine's Java installation.
- Maven is provided by the committed Maven Wrapper (3.9.12).
- Docker must be running for PostgreSQL and required integration tests.

Linux/macOS contributors can use an installed Java 21 JDK, the same Node/npm
versions, and `./mvnw`. The CI runner uses this path. The Java-download setup
script targets Windows; the container smoke test runs in PowerShell on both platforms.

## Windows setup

Run from the repository root:

Stop existing development servers before setup or verification. On Windows,
an active Vite process locks native dependency files that npm ci must replace.

```powershell
docker desktop start
./scripts/setup.ps1
```

Setup installs project-local Java when needed, creates `.env` from the example if
absent, starts PostgreSQL, and installs the locked frontend dependencies.
The example credentials are for local development only. Existing `.env` files
are preserved.

If your execution policy blocks local scripts, invoke them from a PowerShell
session using your organization's approved execution policy; no system policy
change is needed by Backroom.

### Start the backend

```powershell
./scripts/dev-backend.ps1
```

API and health: http://localhost:8080/actuator/health

### Start the frontend in another terminal

```powershell
. ./scripts/env.ps1
npm --prefix frontend run dev
```

Open http://localhost:5173. The page should show **Connected**. Vite proxies
`/api` and `/actuator` to Spring Boot; browser requests stay on one origin.

### Select Java for other Maven commands

```powershell
. ./scripts/env.ps1
java -version
./mvnw.cmd -version
```

Dot-sourcing changes only the current terminal's environment. The helper reads
literal key/value pairs from `.env`; it never evaluates its contents as code.

## Configuration

| Variable | Purpose |
| --- | --- |
| POSTGRES_DB | Local database name |
| POSTGRES_USER | Local database user |
| POSTGRES_PASSWORD | Required local database password |
| POSTGRES_PORT | Host database port; default 5432 |
| SERVER_PORT | Host application port; default 8080 |
| DATABASE_URL | Optional JDBC URL overriding the local database address |
| DATABASE_USERNAME / DATABASE_PASSWORD | Optional credentials for an external database |
| SESSION_COOKIE_SECURE | Set true when deployed behind HTTPS |
| BACKROOM_API_URL | Optional Vite proxy destination |

Compose automatically reads `.env`. Native Spring Boot reads environment
variables; `scripts/env.ps1` loads the same values into your terminal.

The database port is bound to 127.0.0.1. Local HTTP uses non-Secure cookies;
production HTTPS must set `SESSION_COOKIE_SECURE=true`. No production credentials
or provider account is included.

## Verification

```powershell
./scripts/verify.ps1
```

This installs locked frontend dependencies, type-checks and compiles React,
and runs Maven verification against real PostgreSQL Testcontainers. Docker
unavailability is a failure, not a skipped test.

The integration suite verifies PostgreSQL 17 connectivity, Flyway history
initialization and repeat migration safety, minimal public health, anonymous API
rejection, CSRF protection, and blocked administrative endpoints.

There are no business-table migrations yet. Flyway initializes its schema
history; the first versioned business migration belongs to Phase 1.
Hibernate validates the schema and never creates business tables.

## Run the production-style container

Stop a native backend on the same port, then run:

```powershell
./scripts/smoke-test.ps1
```

The multistage build compiles React and packages it inside the Spring Boot JAR.
The final Java 21 image runs as a non-root user. Compose limits it to 512 MB
with half available for the Java heap. Open http://localhost:8080.

The smoke test checks health, the homepage, compiled assets, and the runtime user.
It also creates a uniquely named diagnostic table, recreates the PostgreSQL
container against its existing volume, restarts the application, verifies the
saved value, and removes that diagnostic table.
Existing business data and the named database volume are preserved.

After building once, use `./scripts/smoke-test.ps1 -SkipBuild` to repeat runtime
checks without rebuilding. Containers remain running for inspection.

```powershell
docker compose ps
docker compose logs --tail 80 app
docker stats --no-stream
docker compose --profile app down
```

Stopping containers does not remove the database volume. Keep that volume if you
want to retain local data.

## Linux/macOS equivalent

With Java 21, Node 24, npm 11, and Docker available:

```sh
cp .env.example .env
docker compose up -d --wait db
# Export the values from .env into your shell without committing secrets.
export POSTGRES_DB=backroom POSTGRES_USER=backroom POSTGRES_PASSWORD=backroom_local_only
npm --prefix frontend ci
npm --prefix frontend run build
./mvnw -B -ntp clean verify
./mvnw spring-boot:run
# In another terminal: npm --prefix frontend run dev
```

Use `pwsh ./scripts/smoke-test.ps1` for the same production-container checks.

## Troubleshooting

- **Java 17 is selected:** run `. ./scripts/env.ps1`, then check
  `java -version` and `./mvnw.cmd -version`. Maven enforces Java 21.
- **Docker cannot connect:** start Docker Desktop, wait for
  `docker info` to succeed, and select Linux containers.
- **Port 5432 is occupied:** change POSTGRES_PORT in `.env`, recreate the
  database service with `docker compose up -d --wait db`, and reload
  `. ./scripts/env.ps1` before starting the backend.
- **Port 8080 is occupied:** stop the other backend or change SERVER_PORT in
  `.env`. Reload the environment in both development terminals so Vite follows
  the new backend port.
- **Port 5173 is occupied:** stop the existing frontend. Vite fails clearly
  rather than silently choosing a different port.
- **Database credentials changed after first startup:** PostgreSQL initialization
  variables apply to a new volume. Restore the original values or intentionally
  change the database role password; restarting does not reset existing credentials.
- **Frontend says unavailable:** check backend startup logs, the health URL, and
  the Vite proxy target. A native backend must be started separately.
- **Maven downloads are slow:** the first build downloads its dependencies.
  Subsequent builds use the local cache.
- **npm ci reports EPERM on a native binding:** stop the Vite development server
  in its terminal, then run setup or verification again.
- **Flyway reports no migrations:** expected in Phase 0. Schema history exists;
  Phase 1 introduces business migrations.

## CI and next milestone

GitHub Actions runs frontend compilation, Maven integration tests, and a
production-container smoke test. It does not publish images or deploy services.

Work is prepared on `chore/phase-0-setup`. Phase 1 introduces authentication,
organizations, memberships, and the first business schema. Follow the PRD for
subsequent inventory, scanning, and replenishment milestones.
