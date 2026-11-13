# Operations

Backroom is prepared for deployment but not deployed. No hosting account exists and nothing in this repository creates one. This guide is what to follow when that changes, plus the backup, restore, logging and incident routines that already work against local Compose.

## Contents

- [What a deployment needs](#what-a-deployment-needs)
- [Environment variables](#environment-variables)
- [HTTPS and the label address](#https-and-the-label-address)
- [Choosing a host](#choosing-a-host-0-5-per-month)
- [Deploying](#deploying)
- [Uptime check](#uptime-check)
- [Backups](#backups)
- [Restore drill](#restore-drill)
- [Reading logs by request id](#reading-logs-by-request-id)
- [Incident checklist](#incident-checklist)
- [Measured memory](#measured-memory)

## What a deployment needs

PRD §16 sets the shape: one container and one PostgreSQL database. There is no object storage, broker, Redis or worker.

| Need | Detail |
| --- | --- |
| Container runtime | Runs the image built by the root `Dockerfile`: Java 21 JRE, non-root user, port 8080. |
| Memory | 512 MB for the container. The heap gets half (`-XX:MaxRAMPercentage=50`); the rest is metaspace, threads, PDF generation and page cache. See [measured memory](#measured-memory). |
| PostgreSQL 17 | Managed or self-run. It must not expire or be deleted on a schedule. Flyway migrates it on startup; Hibernate only validates. |
| Connections | At most 4 (Hikari pool), so small free-tier connection limits are fine. |
| HTTPS | A platform router or reverse proxy terminates TLS and forwards `X-Forwarded-Proto` / `X-Forwarded-Host`. |
| A permanent hostname | Printed into every QR label. See [HTTPS and the label address](#https-and-the-label-address). |
| Disk | None. The container keeps no durable files. Labels are generated on demand. |

## Environment variables

| Variable | Production value | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | Loads `application-prod.yaml`, described below. |
| `BACKROOM_PUBLIC_URL` | `https://stock.example.com` | **Required.** In `prod`, startup fails before connecting to the database if it is missing, isn't `https://`, or has a query or fragment. |
| `DATABASE_URL` | `jdbc:postgresql://host:5432/db?sslmode=require` | A JDBC URL, not the `postgres://` form some providers show. Copy host, port and database into this shape. |
| `DATABASE_USERNAME` | provider value | |
| `DATABASE_PASSWORD` | provider value | Store it in the platform's secret store, never in a committed file. |
| `SERVER_PORT` | `8080` | If the platform assigns a port (often `PORT`), set `SERVER_PORT` to that value. |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=50` | Matches `compose.yaml`. Raise it only together with the container memory. |
| `SESSION_COOKIE_SECURE` | not needed | `prod` always sets the Secure flag. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | Compose only | Used by the local `db` service and by the backup scripts. |
| `BACKROOM_IMAGE` | Compose only | Image tag for the `app` service. The default is `backroom:local`. A VPS can pin a release tag here. |

`application-prod.yaml` sets:

- **Cookies and proxies:**
  - The session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`.
  - `server.forward-headers-strategy: framework` trusts the proxy's `X-Forwarded-*` headers, so the app knows requests arrived over HTTPS.
- **Database pool:** Hikari keeps at most 4 connections and holds none idle.
- **Shutdown:** graceful, with up to 20 s for in-flight requests after SIGTERM.
- **Health:**
  - The liveness and readiness probes are on. Readiness includes the database.
  - `/actuator/health` stays the only exposed actuator endpoint.
  - Responses show only `{"status":"UP"}`, never details or components.
- **Logs:** `logging.structured.format.console: ecs` writes one JSON object per line.

## HTTPS and the label address

Two reasons HTTPS is not optional:

- Phone browsers only allow camera scanning on secure origins.
- The session cookie is `Secure`, so sign-in can't work over plain HTTP.

`BACKROOM_PUBLIC_URL` is printed into every QR label as `<BACKROOM_PUBLIC_URL>/s/<token>`. Labels live on shelves for years, so this address must stay the same:

- **Use a hostname you control,** such as `stock.yourbusiness.com` pointed at the host with a CNAME. Don't use the provider's generated `*.something.app` name. Changing providers then means changing one DNS record, not reprinting every label.
- **Decide it before printing real labels.** A label printed with a different address only resolves if that address keeps working.
- **Scanning checks it.** The scanner treats only links that start with this exact address as Backroom links. Anything else is ignored, never followed.

## Choosing a host ($0–5 per month)

PRD §16 targets roughly $0–5/month for the demo. Free tiers change often, so **check each provider's current allowances on the day you deploy**. Don't rely on this document or on memory. Check:

1. **Runs a Dockerfile** and gives the container 512 MB. Less memory will run out (see [measured memory](#measured-memory)).
2. **The database is permanent.** Reject trial credits, databases that expire after N days, or plans that delete idle databases (PRD §16).
3. **Database backups or point-in-time recovery** on the plan. If there are none, the [backup schedule](#backups) below is the only safety net.
4. **Automatic HTTPS on a custom domain.**
5. **Sleeping.** A service that sleeps when idle is acceptable for a clearly labelled portfolio demo. The first scan after a sleep takes as long as startup. It is not acceptable once a business depends on it. Budget for an always-on plan before that (PRD §16).
6. **Connection limits** of at least 5: 4 for the pool plus 1 for a backup.
7. **Region** close to the business, with the database and app in the same region.

Two shapes fit the budget:

- **Platform plus managed PostgreSQL.** The platform builds the Dockerfile from the repository and the database is a separate managed service. Least upkeep. Check items 2, 3 and 5 most carefully.
- **One small VPS** (≈1 GB RAM) running this repository's `compose.yaml`, with a TLS reverse proxy such as Caddy in front. You run the OS updates and backups yourself. `scripts/backup.ps1` works there unchanged under PowerShell 7 (`pwsh`).

Record the chosen provider, plan, region and the allowances you checked, with the date, at the end of this file when deploying.

## Deploying

1. **Point the hostname at the host.** Create the DNS record and confirm that HTTPS works.
2. **Create the database.** Copy its connection details into `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
3. **Set the variables** in the table above, with `SPRING_PROFILES_ACTIVE=prod` and `BACKROOM_PUBLIC_URL` set to the permanent HTTPS address.
4. **Deploy the image.** CI builds it on every push and runs the smoke test, the restore drill and the browser suite against it. Deploy only a commit whose CI run is green.
5. **Watch startup.** Flyway applies any pending migrations and then the readiness probe turns `UP`. If startup fails, the log says why. A missing `BACKROOM_PUBLIC_URL` is reported by name.
6. **Check the deployment:**
   - `https://<host>/actuator/health/readiness` returns `{"status":"UP"}`.
   - Signing in works.
   - A printed test label scans on a phone.
7. **Set up the [uptime check](#uptime-check) and the [backup schedule](#backups).**

Migrations only move forward, and every release must start against the previous release's data. To roll back, redeploy the previous image. If a migration has already run, restore the pre-deploy backup instead (see the [incident checklist](#incident-checklist)). So **take a backup before every deploy that adds a migration.**

## Uptime check

Point any free HTTP monitor at:

```
GET https://<host>/actuator/health/readiness   → 200 {"status":"UP"}
```

- **Readiness, not liveness.** Readiness returns `503` when the database is unreachable (`{"status":"DOWN"}`) or the app is shutting down (`{"status":"OUT_OF_SERVICE"}`). Liveness (`/actuator/health/liveness`) only answers "is the JVM up?" and is what a platform restart policy should use.
- **Check every 5 minutes or less often.** Free database tiers bill or throttle compute, and a check that runs every few seconds keeps a sleeping database awake (PRD §16: avoid unnecessary polling).
- **Alert after 2 consecutive failures.** One slow cold start shouldn't page anyone.

## Backups

`scripts/backup.ps1` runs `pg_dump -Fc` inside the Compose `db` container, so it needs no local PostgreSQL tools:

- **Where:** it writes `backups/backroom-YYYYMMDD-HHmm.dump`.
- **Check:** it confirms that `pg_restore --list` can read the dump.
- **Retention:** it deletes all but the newest 14. Set `-Keep` to change that.
- **Git:** `backups/` is ignored, because dumps contain business data.

```powershell
./scripts/backup.ps1                 # live database (POSTGRES_DB)
./scripts/backup.ps1 -Keep 30        # longer retention
```

**Item photos** are stored in the database (about 100–200 KB each after the browser resizes them), so they are in every dump. A catalog of 500 photographed items adds roughly 50–100 MB to each backup; plan retention and host storage accordingly.

**Schedule:**

| When | What |
| --- | --- |
| Daily, outside opening hours | `backup.ps1` (14 kept = two weeks) |
| Before every deploy with a migration | `backup.ps1`, and note the file name in the deploy notes |
| Weekly | Copy the newest dump off the machine (a different provider or an encrypted drive) |
| Monthly, and after any schema change | [Restore drill](#restore-drill) |

On Windows, schedule it with Task Scheduler. The action is `powershell.exe -NoProfile -File <repo>\scripts\backup.ps1`, run from the repository folder. On a Linux VPS, use cron with `pwsh -File`.

**Hosted PostgreSQL** has no Compose `db` container. Use the provider's own backups if the plan includes them, and also take a weekly portable dump with the same PostgreSQL 17 client image:

```powershell
docker run --rm -v "${PWD}/backups:/backups" postgres:17-alpine `
  pg_dump -Fc --no-owner -f /backups/backroom-manual.dump `
  "postgresql://USER@HOST:5432/DB?sslmode=require"
```

Pass the password through the `PGPASSWORD` environment variable (`-e PGPASSWORD`), not in the URL, so it stays out of shell history.

### Restoring

```powershell
./scripts/restore.ps1 -File backups/backroom-20261112-0300.dump -Database backroom_inspect
```

- **Target:** the named database is dropped and recreated, then the dump is restored without owners or privileges.
- **The live database (`POSTGRES_DB`) is refused** unless you pass `-Force`. To replace live data:
  1. Stop the app: `docker compose --profile app stop app`.
  2. Run the restore with `-Database backroom -Force`.
  3. Start the app again. Flyway validates the restored schema on startup.

## Restore drill

A backup counts only once it has been restored. `scripts/restore-drill.ps1` does the whole drill and exits non-zero on any problem:

1. **Count rows** in every table of the live database, back it up to `backups/drill/`, and count again. If the counts moved during the dump, it retries up to 3 times. Drill dumps keep 3 and never rotate out real backups.
2. **Restore** the dump into a scratch database named `backroom_drill_<random>`.
3. **Compare row counts** of every table. Tables are found from the schema, so new migrations are covered automatically.
4. **Check the ledger invariants** on both the restored and the live database:
   - Every `stock_balances.quantity` equals the sum of `stock_movements.quantity_change` for its item and location.
   - Every `restock_requests.received_quantity` equals the sum of its `restock_receipts` that haven't been reversed.
5. **Drop** the scratch database, including after a failure.

CI runs the drill after the container smoke test on every push.

**Last local run** (2026-11-12, development data in the local Compose database):

```
Row counts (backroom -> backroom_drill_fd2a8acb5c7d):
  audit_events 99/99, flyway_schema_history 6/6, invitations 0/0, item_placements 14/14,
  items 30/30, locations 29/29, memberships 8/8, organizations 8/8, resource_codes 2/2,
  restock_receipts 2/2, restock_requests 6/6, stock_balances 14/14, stock_counts 1/1,
  stock_movements 12/12, stock_operations 11/11, users 8/8
Ledger invariants: 14 stock slots, 0 out of balance; 6 restock requests, 0 out of balance (restored and live)
PASS: restore drill. 16 tables and 250 rows matched after restoring backroom-20261112-0009.dump (77.2 KB)
```

## Reading logs by request id

The first filter on every request gives it a request id:

- **Source:** a valid incoming `X-Request-Id` is kept (1–64 characters of `A–Z a–z 0–9 . _ -`). Anything else is replaced with a new UUID, so a client can't forge log lines or flood them.
- **Logs:** the id is in the logging MDC as `requestId`, so every log line of that request carries it.
- **Response header:** the id is echoed as `X-Request-Id`.
- **Error body:** the id is added to `500` problem responses as `"requestId"`.

People reporting a failure can quote the `requestId`. It is in the browser's network panel, on the failed request's response.

In production every log line is one JSON object (ECS), for example:

```json
{"@timestamp":"2026-11-12T08:47:12.883Z","log":{"level":"INFO","logger":"com.backroom.inventory.StockService"},"process":{"pid":1,"thread":{"name":"http-nio-8080-exec-2"}},"service":{"name":"backroom","version":"0.1.0-SNAPSHOT","node":{}},"message":"Stock operation recorded: type=RECEIPT operationKey=… organizationId=…","requestId":"3f0c2a9e-…","ecs":{"version":"8.11"}}
```

`requestId` is a top-level field. To trace every request while investigating, set `LOGGING_LEVEL_WEB=DEBUG` temporarily: each request then logs its method and path with the same `requestId`. Remove the variable afterwards.

To find one request:

```powershell
docker compose logs app | Select-String '"requestId":"3f0c2a9e-'      # Compose / VPS
```

On a platform, paste the id into its log search. Locally, the plain-text development logs show the id in brackets after the level: `INFO [3f0c…]`.

**What gets logged:**

- **Stock and deliveries:** each stock command and each restock delivery logs its operation type, operation key and organization id at INFO. Replays of the same key are logged as replays.
- **Never logged:** names, emails, notes, quantities, passwords, session ids, CSRF tokens or invitation/QR tokens.
- **Unexpected failures:** these log one ERROR with the stack trace. The response body shows only a generic message and the request id.

## Incident checklist

1. **Confirm.** Open `/actuator/health/readiness` and the home page from a phone network as well as your own.
2. **Classify:**
   - **`503` on readiness:** the app can't reach the database. Check the provider's database status, connection limits (4 for the pool plus any open admin sessions) and credentials.
   - **No response:** the app is down. Check that the platform shows the container running and whether it was killed for memory. An out-of-memory kill appears as exit code 137 or an "OOMKilled" event. See [measured memory](#measured-memory).
   - **A specific action fails:** get the `requestId` from whoever saw it and find its log lines.
3. **Stop the damage.**
   - If a deploy started the problem, redeploy the previous image.
   - If a migration ran, take a fresh backup *first*, so nothing recorded since is lost. Then decide between rolling the schema forward with a fix and restoring the pre-deploy backup.
4. **Restore if data is wrong.**
   - First restore the newest good backup into an inspection database (`restore.ps1 -Database backroom_inspect`) and compare. Only then replace live data (`-Force`, with the app stopped).
   - Stock is append-only. A wrong stock entry is corrected in the app with a reversal or a count, not by editing the database.
5. **Verify.**
   - Readiness is `UP`.
   - Sign-in works and a label scans.
   - The restore drill passes if a restore was involved.
6. **Write down** what happened, the request ids, the time window and what changed. Add anything new to this checklist.

## Measured memory

Measured with `scripts/measure-memory.ps1`. It runs the full browser suite (`scripts/e2e.ps1 -SkipBuild`) against the production image and samples `docker stats` and the cgroup `memory.peak` of the app container about once a second. The container has `mem_limit: 512m` and `-XX:MaxRAMPercentage=50`.

**Run on 2026-11-12** on a Windows 11 laptop (4 logical CPUs) with Docker Desktop. The image is `backroom:ops`, built from this branch. The 5 browser tests passed in 1.6 min.

| Measure | Value | Share of 512 MiB |
| --- | --- | --- |
| First sample, just after the container started | 33 MiB | 6% |
| Average across the run (34 samples over 169 s) | 279 MiB | 54% |
| Peak, `docker stats` (excludes reclaimable page cache) | **351 MiB** | **68%** |
| Peak, cgroup `memory.peak` (includes page cache) | 356 MiB | 70% |

**What this means:**

- **Headroom:** about 160 MiB is left under the limit. That holds with the 256 MiB heap cap (50%) plus non-heap memory: metaspace, code cache, thread stacks, PDFBox and ZXing buffers.
- **Smaller containers:** a 256 MB container would not fit. 512 MB is the minimum to choose on a host.
- **Reproducibility:**
  - An earlier run on the same machine while it was CPU-saturated reached 330 MiB `docker stats` and 338 MiB cgroup peak before Playwright timed out. Memory was the same under load; only timing changed.
  - On a busy machine the app can take over 70 s to start. `scripts/e2e.ps1` waits a limited time for health, so measure on a quiet machine.

Rerun after dependency upgrades or new heavy features (larger label batches, exports):

```powershell
./scripts/measure-memory.ps1 -Build
```
