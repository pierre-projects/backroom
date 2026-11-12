# Phase 1 foundation

## What is available

Register, sign in, create and switch organizations, organize locations, maintain a
catalog and expected item placements, invite staff, manage access, and review
activity. Owners and Managers edit catalog/locations; Employees have read access.
Only Owners manage staff and organization settings. Stock quantities and
transactions, labels/scanning, restock workflows, and equipment are later phases.

PRD v1 is preserved. This document records implementation defaults separately.

## Authentication and sessions

- Accounts use a stripped, lowercase email identifier and a display name.
- Email ownership is not verified. There is no self-service password recovery.
  Establish a recovery and verification process before real business reliance.
- Passwords require at least 12 Unicode characters and at most 72 UTF-8 bytes.
  Spring's delegating BCrypt encoder stores an algorithm-prefixed hash. Submitted
  credentials are erased from the session principal and excluded from its string
  representation.
- Standard Spring Security form authentication consumes email/password at
  POST /api/v1/auth/login. Success and failure are JSON, not redirects.
- React obtains a session-backed CSRF token on initialization. Registration is
  followed by ordinary login, with fresh tokens before authentication and after
  login/logout.
- The session principal stores identity, never organization roles. Every
  organization operation checks the current database membership.
- Sessions are in memory with a 30-minute idle timeout. Application restarts
  invalidate sessions; accounts, memberships, and catalog data remain in PostgreSQL.
- Cookies are HttpOnly and SameSite=Lax. Set SESSION_COOKIE_SECURE=true with HTTPS.
- CSRF remains enabled for registration, login, invitations, and all other unsafe
  requests. The API client refreshes an expired token for the next attempt without
  automatically replaying a failed mutation.

## Authorization and transactions

An organization's initial Owner membership and creation activity commit together.
Owner changes acquire the organization row lock before re-reading authorization
and checking the number of active Owners. Hierarchy changes and placement writes
use the same organization lock to prevent cycles and leaf-placement races.

Invitation acceptance locks the organization before the invitation and reloads
its current state. It cannot consume a link twice. Deactivating a membership keeps
the original row for historical attribution and denies subsequent requests using
existing sessions. A fresh invitation can reactivate that membership.

Services write activity in the business transaction. A failure writing activity
rolls back the mutation. Audit services do not call access repositories: the
calling application service authorizes their record/query operations.

## Invitations

Owners issue Manager or Employee invitations. Existing active members can be
promoted to Owner through member management.

Links carry a random 32-byte token in the URL fragment. Only its SHA-256 digest is
stored. Links expire after seven days; expiry is checked when used and requires
no scheduled job. A link is returned once at creation, never in later lists or
activity. The browser preserves it through sign-in or registration. Preview and
acceptance send it in a CSRF-protected request body, never a server URL.

Anyone signed in and holding a pending link can accept after reviewing the
business and role. Existing active members cannot change roles through invitation
acceptance. Used, expired, and revoked links cannot grant access.

## Catalog and storage

Units: EACH, GRAM, KILOGRAM, MILLILITER, LITER. There is no unit conversion.
Units remain editable before stock tracking exists; Phase 2 must freeze their
meaning once stock movements exist. EACH is a whole-quantity unit; measured units
will support up to three decimal places when quantities ship.

SKUs are stripped and uppercased. Blank SKUs become null. Uniqueness is per
organization and includes archived records. Names need not be unique.

A placement means an item is expected at a location, not that its quantity has
been counted. Only active leaf locations can receive active placements.
Locations expose a small mutation-policy interface that inventory implements;
there is no reverse dependency on inventory repositories.

Archiving an item deactivates its placements. Removing a placement preserves its
row. Locations cannot be archived with active children or placements. No hard
deletion or restore UI is provided. Archived records remain readable with an
archived flag and may be included in list results.

Metadata edits and archive commands include expectedVersion; stale versions
return 409. Phase 2 must additionally block archival/removal while stock remains.

## API conventions

All organization routes begin /api/v1/organizations/{orgId}. List endpoints return
items, page, size, totalElements, totalPages; pages are zero-based, default size
20, maximum 100. Page and size outside the contract return 400.

| Area | Methods and suffixes |
| --- | --- |
| Authentication | GET /api/v1/auth/csrf, GET /me, POST /register, POST /login, POST /logout (all auth routes share /api/v1/auth) |
| Organizations | GET/POST /api/v1/organizations, GET/PATCH /{orgId} |
| Members | GET /members, PATCH/DELETE /members/{userId} |
| Invitations | GET/POST /invitations, POST /invitations/{id}/revoke |
| Acceptance | POST /api/v1/invitations/preview and /accept with a token body |
| Locations | GET/POST /locations, GET/PATCH /locations/{id}, POST /locations/{id}/archive |
| Location contents | GET /locations/{id}/items |
| Items | GET/POST /items, GET/PATCH /items/{id}, POST /items/{id}/archive |
| Placements | GET /items/{id}/placements, PUT/DELETE /items/{id}/placements/{locationId} |
| Activity | GET /audit-events |

Location lists accept parentId, rootsOnly, leafOnly, includeArchived, and q.
Catalog lists accept q and includeArchived. The frontend traverses paginated
results for selection controls rather than truncating them to one page.

Errors use application/problem+json with errorCode and optional fieldErrors.
400 means invalid input, 401 unauthenticated/invalid credentials, 403 denied
role/CSRF, 404 absent or inaccessible resource, and 409 conflicting state.
Security handlers and MVC handlers use the same problem format.

Frontend routes are /signin, /register, /invite, and /app. Organization selection
is in /app/organizations/{orgId}/..., never the session. Only those frontend
routes forward to index.html; unknown API paths and missing assets remain errors.

## Verification

- scripts/verify.ps1 runs frontend checks and PostgreSQL integration tests.
- scripts/smoke-test.ps1 checks the production container against the development
  database, preserving its volume across container recreation.
- scripts/e2e.ps1 builds and runs a uniquely named Compose project with random
  host ports, temporary credentials, and a separate database volume. It verifies
  browser onboarding, placement, invitations, role restrictions, deactivation,
  organization switching, metadata editing, archival, route refresh, logout/session
  expiry, and a phone-sized viewport.
- The E2E wrapper always tears down only its explicitly named project and volume.
  It uses installed Chrome on Windows when available; otherwise it installs
  Playwright Chromium (and Linux dependencies).
- Use scripts/e2e.ps1 -SkipBuild only after building the current application image.
- Docker-dependent checks fail if Docker is unavailable. No default accounts,
  production credentials, email service, or paid infrastructure are introduced.
