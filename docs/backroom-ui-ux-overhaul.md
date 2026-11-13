# Backroom — UI/UX overhaul & backend opportunity review

> **Purpose:** Audit the existing Backroom repository, improve UX on top of what already ships, and decide whether further backend work is justified. This is a product and implementation brief for an **evolving product**, not a greenfield rebuild mandate.

**Read first (source of truth in this repo):**

- [README](../README.md) — product summary and setup
- [Architecture](architecture.md) — modular monolith, packages, what exists vs future `assets`
- [Design system](design-system.md) + [frontend codebook](frontend-codebook.md) — tokens and UI rules already in force
- [Sortly inspiration](sortly-inspiration.md) — primary browse/UX rules (guidance; not all shipped in UI yet)
- Phase contracts: [1](phase-1.md) · [2](phase-2.md) · [3](phase-3.md) · [4](phase-4.md) · [5](phase-5.md)

---

## 1. Product direction

Backroom is a lightweight operations system for small businesses that need to **organize, find, use, move, and maintain the physical things they rely on**.

- **Primary use case:** Internal supplies, consumables, tools, equipment, and storage spaces.
- **Secondary use case:** Items may occasionally be sold. Today stock-outs are **Usage** or **Waste** only. A `Sold` (or similar) reason is a *possible extension* — do not invent POS, storefronts, accounting, or order management.
- **Core idea:** Help an owner or employee answer: *What do we have? Where is it? How much is left? Who changed it? Does anything need attention?*
- **Target users:** Cafés, workshops, repair shops, studios, small venues, and similar businesses. Avoid assuming every business operates like a warehouse or retail store.
- **Non-negotiable technology:** **Java + Spring Boot stays the backend.** Do not recommend replacing it. Prefer meaningful work *inside* the existing Spring Boot modular monolith.

### Product principles

1. **Physical spaces first.** Browsing a room, shelf, or bin should feel as natural as browsing items. (Locations already nest; UX should make that walkable.)
2. **Simple by default; powerful when needed.** Basic tasks should not require configuring many fields.
3. **Make daily actions obvious.** Receive, use, waste, move, count, scan, and locate should be easy to reach — using Backroom’s existing verbs where they already exist.
4. **A clear record of change.** Stock movements and activity history already exist; surface them clearly.
5. **Work across devices.** Desktop for oversight and setup; mobile for scanning and on-site updates. Shell already uses sidebar + bottom tabs.
6. **Avoid enterprise clutter.** Progressive disclosure; don’t invent ERP modules “for completeness.”

---

## 2. Current product reality (do not rediscover from scratch)

Phases **1–5 are implemented** in this repository. Treat the following as **baseline**, not as wishlist items to re-propose as if missing.

### Stack

| Layer | Reality |
| --- | --- |
| Backend | Java 21, Spring Boot modular monolith (`com.backroom.*`), PostgreSQL 17, Flyway, JPA validate-only |
| Frontend | React 19 + TypeScript + Vite; plain CSS tokens in `frontend/src/styles.css` / `workspace.css` (no separate component library package) |
| Auth | Email/password, session cookie + CSRF; roles from org membership (`OWNER` / `MANAGER` / `EMPLOYEE`); org id in URL `/app/organizations/{orgId}/…` |
| Packaging | SPA built into the same JAR |

### Navigation that already ships

Primary tabs (all roles), from `frontend/src/workspace.tsx`:

| Label | Route segment | Job today |
| --- | --- | --- |
| **Home** | `home` | Attention: search, low stock, requests, setup checklist, recent activity (managers+) |
| **Inventory** | `items` | Quantity-tracked catalog; filters for location / low / archived |
| **Scan** | `scan` | Camera + manual code entry → open or stock sheets (does not silently mutate) |
| **Requests** | `requests` | Internal restock requests (not supplier POs) |
| **Locations** | `locations` | Nested spaces: Room / Shelf / Cabinet / Bin |

Administration (account menu on phone; sidebar group on desktop):

| Label | Who |
| --- | --- |
| Activity | Owner, Manager |
| Export data | Owner, Manager |
| People | Owner |
| Business settings | Owner |

Also routed: item/location/request detail, labels print flow, search. Public deep link: `/s/{token}` for printed QR codes.

### Domain that already exists

| Area | What is true today |
| --- | --- |
| **Organizations** | Multi-business; switch business |
| **People** | Invite Manager/Employee; fixed three roles; owner password-reset *links* (no email delivery) |
| **Locations** | Nested `parentId`; types ROOM/SHELF/CABINET/BIN; archive; leaf placements only |
| **Items** | Name, optional SKU, unit (EACH/g/kg/mL/L); archive; on-hand rollup; low flag |
| **Placements** | Expected item ↔ leaf location; optional **minimum** per placement |
| **Stock ledger** | Immutable operations/movements; balances never edited directly; reversals for corrections |
| **Stock ops** | Opening, Receive, Usage, Waste, Transfer, Count (+ Reverse for managers) |
| **Codes / labels** | Generated QR + manufacturer barcodes; PDF label layouts; Scan tab |
| **Restock** | Request → approve/reject → receive deliveries; close short |
| **Search** | Items, locations, code match |
| **Exports** | CSV: items, stock by location, movements, requests |
| **Audit** | Org activity feed for Owner/Manager |

### Explicitly **not** in the product today

Do not write UI that pretends these exist. Mark them as **gap / opportunity / defer** after audit:

- Item or location **photos** / media upload
- **Grid / list** browse toggle and photo-first cards (documented in Sortly inspiration; UI is still tables)
- **Categories, tags, custom fields**
- Separate **Equipment / assets** entity (architecture lists a future `assets` package; no code package yet)
- **Checkout / assignment / custody** (“who has this tool”)
- **Sold** as a stock-out reason (only USAGE / WASTE)
- **Suppliers, purchase orders, pricing/cost, POS**
- **Import / bulk upload**
- Soft **restore** UI for archives; self-service email password recovery

### Stock language already in the UI (prefer these verbs)

| Operation | UI label (keep unless renaming is justified) |
| --- | --- |
| RECEIPT | **Receive stock** |
| USAGE | **Record usage** |
| WASTE | **Record waste** |
| TRANSFER | **Transfer stock** |
| COUNT | **Count stock** |
| OPENING | **Set starting quantity** |
| REVERSAL | **Reverse** |

Removals use reason enum `USAGE` | `WASTE` only, plus optional note. Receive may include optional free-text **Reference**.

### Design system already in force

- Neutral canvas + **one teal accent** (`--accent`); not Sortly red.
- System UI fonts; border-first panels; bottom sheets on phones; AA contrast targets.
- Evolve tokens/components in place (`styles.css`, `components.tsx`, `icons.tsx`). Do **not** introduce a parallel design system or new UI framework unless the audit proves the current one cannot support the direction.

---

## 3. Competitor references: borrow patterns, not visual identity

### Sortly — primary UI and UX inspiration

Reference: https://www.sortly.com/ · Repo brief: [sortly-inspiration.md](sortly-inspiration.md)

Explore / apply:

- Photo-first item cards and recognizable folder/**space** navigation.
- Easy grid/list switching, search, filters, and breadcrumbs.
- Focused item details with quantities, locations, status, and history.
- Short, approachable add-item flows with optional advanced fields.
- Mobile-first quick actions without a stretched-phone desktop.

**Backroom adaptation:** Clarity and visual browsing, but prioritize **physical spaces**, **actionable stock changes**, and **things needing attention**. Map Sortly “folders” onto existing **Locations**, not a second taxonomy. Keep Backroom chrome and tokens.

### Shelf — domain modeling and physical workflows

Reference: https://www.shelf.nu/

Explore:

- Distinguishing **individual assets** (equipment/tools) from **quantity-tracked stock** (consumables).
- Nested physical locations and location-specific contents (Backroom already has nesting + contents).
- QR labels that open the correct asset or location (**location and item QR already ship**).
- Asset condition, assignment/custody, status, and maintenance history (**not built** — treat as opportunity assessment).
- Moving equipment and supplies between places (**transfers exist for quantity stock**).

**Backroom adaptation:** Locations are already meaningful entities. A QR on a shelf already opens that location; a QR on an item opens that item. Equipment/custody is the open modeling question — not location identity.

### BoxHero — transactions and small-business onboarding

Reference: https://www.boxhero.io/

Explore:

- Straightforward stock in/out, movement, and count workflows (**already present** with Backroom verbs).
- Auditable transaction history (**movements + activity exist**).
- Fast item creation, scanning, bulk entry/import (create + scan exist; **import does not**).
- Onboarding from workspace to first useful inventory action (**Home setup checklist exists**).

**Backroom adaptation:** Prefer the product’s existing everyday language (Receive / Record usage / Transfer / Count). Optional stock-out reasons beyond Usage/Waste (e.g. Sold) are an extension of the removal reason model — not a POS.

---

## 4. Target information architecture (evolve, don’t replace blindly)

**Start from the shipped nav.** Propose renames or merges only with evidence from user journeys.

| Target concept | Closest current surface | Guidance |
| --- | --- | --- |
| Overview | **Home** | Strengthen attention (low stock, requests, next actions). Do not replace with vanity KPI dashboards. |
| Spaces | **Locations** | Space browsing, breadcrumbs, contents, labels — Sortly-like walkability on top of existing hierarchy. |
| Inventory | **Inventory** (`items`) | Quantity-tracked supplies/consumables (current item model). |
| Equipment | *Missing* | Only introduce a separate section if the audit justifies an `assets` (or similar) model; otherwise a unified Items view with clear status may be enough — **explain the decision**. |
| Activity | **Activity** + item/location movement history | Don’t duplicate ledgers; clarify where users look. |
| Scan | **Scan** + `/s/{token}` | Already first-class; improve resolution → action, not a greenfield scanner. |
| Requests | **Requests** | Keep internal restock distinct from imaginary supplier POs. |
| Settings / People / Exports | Existing admin destinations | Progressive disclosure only. |

Avoid splitting Inventory and Equipment **if** a single catalog remains clearer given current data. If Equipment is deferred, say so explicitly and keep UI honest.

---

## 5. Priority screens and UX requirements

Requirements below are **targets relative to current behavior**. Call out what is already met vs gap.

### A. Overview / Home

Already has: search, low stock, requests summary, setup checklist, recent activity (managers+), Scan / Request shortcuts.

Improve toward:

- Owner/employee immediately sees what needs attention and can act (Request, open item, Scan).
- Shortcuts stay honest: **Add item**, **Receive / Record usage**, **Scan**, **Add location** — wired to real routes/sheets.
- Quick entry into main rooms/spaces.
- **Do not** lead with inventory valuation or dense charts.

### B. Spaces / location detail

Already has: nested browse, type badges, child locations, expected items, codes panel, stock update from contents, label selection.

Improve toward:

- Cards/list + **breadcrumbs** (path beyond a single Back link).
- Clearer space → contents browsing (Sortly folder feel, physical naming).
- Search within a space if missing or weak.
- Create / rename / move / archive with strong empty states (move/parent edit already partly exist — verify in UI).
- Printed QR for spaces (exists); scanning a location should land on contents and relevant actions (**Count this space** only if productized — today count is per item at a location).

### C. Inventory / item detail (consumables)

Already has: name, SKU, unit, on-hand, stock by location with parent path, receive/usage/waste/transfer/count/opening, minimums, requests panel, codes, movement history, archive rules.

Improve toward:

- Photo/visual-first browse on the list; focused detail hero (qty, places, status, history).
- High-visibility actions using **existing** verbs.
- Short add-item flow (name + unit required; SKU optional/advanced).
- Human-readable timeline (history exists — improve clarity, reasons, empty states).

### D. Equipment / individual assets

**Not shipped.** Treat as opportunity review:

- Only design screens if recommending a backend `assets` (or dual-mode item) model.
- If deferred: do not put “Checked out / Damaged / Assign” badges in the live UI.
- Keep any first-pass custody/maintenance proportional; no enterprise CMMS.

### E. Scan and transaction flow

Already has: Scan tab, camera + manual entry, manufacturer barcode registration (managers), deep links, stock sheets with operation keys / idempotency, role-gated actions, non-negative balances.

Improve toward:

- Faster path from scan → correct record → confirm action.
- Clearer fallbacks when camera is denied.
- One-handed comfort on small screens (sheets already; polish).
- Never imply scan auto-decrements stock without confirmation (current design is correct — preserve it).

### F. Onboarding

Already has: register → create org → Home checklist (locations → items → record stock → labels/scan → invite).

Improve toward:

- Shorter perceived time to first value; fewer optional fields on first create.
- Do not require categories, suppliers, or complex settings (they don’t exist — don’t add them for onboarding).

---

## 6. Visual design direction

Align with [design-system.md](design-system.md) and [sortly-inspiration.md](sortly-inspiration.md):

- Clean, calm, warm-neutral UI; teal accent for act/here only.
- Card-driven, **photo-aware** browsing where it helps; keep dense **list/table** mode for comparison.
- Consistent spacing, type, icons, buttons, states (extend existing components).
- Responsive: existing sidebar + bottom tab bar — adjust labels/IA carefully.
- Badges for **Low**, **Archived**, request states already; add new status badges only when data exists.
- Strong empty / loading / error / success / permission states (patterns exist — raise consistency).
- Accessibility: focus, keyboard, contrast, labels, 44px targets — already required; don’t regress.
- Avoid gradients-as-product, heavy shadows, cluttered dashboards, decorative charts.
- **Evolve** the existing design system; don’t fork a second one.

---

## 7. Backend opportunity review — required

**Do not assume the backend cannot support new flows, and do not assume it must stay unchanged.** Audit actual APIs, Flyway schema, services, authorization, and stock concurrency before deciding.

For each proposed UX capability:

1. **Supported now?** Works with existing endpoints/data without awkward workarounds?
2. **Frontend-only?** Presentation, composition, or a minor API tweak enough?
3. **Backend opportunity?** New model, relationship, endpoint, validation, or migration clearly improves correctness/usability?
4. **Trade-offs?** Cost, migration risk, complexity, maintenance.
5. **Recommendation?** Keep / small extension / refactor within Spring Boot / defer.

### Inspect against reality (not a blank checklist)

| Capability | Current reality to verify | Weigh |
| --- | --- | --- |
| Nested locations | **Exists** (`parentId`, cycle checks, leaf placements) | UX (breadcrumbs, cards) vs schema changes |
| Consumables vs assets | **Single item model** today; `assets` package planned only in architecture | Unified UX vs new module |
| Stock transaction ledger | **Exists** (immutable movements/ops, reversals, triggers) | Presentation vs new event types |
| Moves between spaces | **Transfers exist** (atomic, dual movements) | UX clarity; multi-item moves? |
| QR / barcode resolution | **Exists** (generated tokens, GTINs, `/s/{token}`, scan-resolutions) | Faster UX; more code kinds? |
| Equipment assignment | **Absent** | Lightweight custody vs defer |
| Low-stock alerts | **Exists** (per-placement minimums, Home + filters) | Notification channels? (email absent) |
| Multi-business access | **Exists** (org-scoped APIs + membership roles) | Don’t break tenant isolation |
| Restock / “purchase” | **Internal requests**, not supplier POs | Don’t rebrand into purchasing accidentally |
| Import / bulk | **Absent** (CSV *export* exists) | Defer vs onboarding win |
| Photos | **Absent** (no object storage in architecture) | Large platform cost — justify or defer |
| Sold / extra removal reasons | Removals: **USAGE \| WASTE** only | Small enum extension vs POS creep |
| Grid/list + visual browse | Frontend-only relative to current APIs | Prefer FE unless photos need storage APIs |

### Decision rules

- **Favor backend change** when it unlocks a high-value workflow, enforces integrity, removes fragile FE workarounds, or creates a clean reusable domain concept **inside** Spring Boot modules.
- **Favor frontend-only** when the API is already reliable and the issue is navigation, presentation, forms, or interaction (most Sortly browse work lands here).
- **Defer** when low priority, speculative, expensive to migrate, or ERP/POS-shaped.
- Preserve **Java + Spring Boot**. Respect module boundaries in [architecture.md](architecture.md) (inventory owns balances; restocking uses inventory services; no cross-module repository bypass).
- No breaking API/schema changes without consumers, migrations, and compatibility notes.
- Recommend Flyway migrations, validation, transaction boundaries, permissions, and tests where needed.
- **Don’t invent support for absent features.** Mark unknowns and verify in the repo.

---

## 8. Repository audit and execution plan

### Phase A — Inspect before redesigning

Review:

- Frontend routes, screens, `components.tsx` / CSS tokens, responsive shell.
- Journeys: setup checklist, browse locations/items, receive/use/transfer/count, scan, requests, labels.
- Spring Boot controllers/services/entities, security, idempotency/concurrency (Phase 2), tests.
- Flyway migrations and constraints.
- Phase docs vs running UI — note drift.

Produce a **current-state audit**: works / confusing / missing / technically blocked.

### Phase B — Propose the overhaul

Deliver:

1. Prioritized screen-by-screen UX plan **anchored to existing screens**.
2. Revised nav/IA with reasoning (rename vs keep).
3. Design-system **deltas** (new components/tokens only) — not a rewrite of [design-system.md](design-system.md).
4. User-flow map for core actions using **current stock verbs**.
5. Frontend ↔ API matrix: **supported / gap / proposed change / effort / risk / priority**.
6. Backend opportunity assessment (decision rules above).
7. Phased implementation plan with dependencies and acceptance criteria.

Separate **quick wins** (mostly FE: breadcrumbs, grid/list, add-item brevity, Home polish) from **schema/API** work (photos, assets, sold reason, import).

### Phase C — Implement in sensible slices

When implementing, prefer vertical slices. Preserve working Phase 1–5 behavior unless documented.

Recommended order **adjusted to this repo**:

1. Design-system deltas + shared browse patterns (tiles, grid/list, breadcrumbs) on **Inventory** and **Locations**.
2. **Home** attention polish and space entry points.
3. **Item detail** / stock action clarity (existing endpoints).
4. Scan → action path and label flows polish.
5. Onboarding, empty states, a11y/responsive pass.
6. Only then: approved backend opportunities (e.g. removal reasons, assets module, media) with migrations and tests.

Avoid a large visual rewrite that breaks stock, scan, or requests.

---

## 9. Definition of done

- A new user can create a business, location, and first item and record stock without assistance (already possible — regressions forbidden; UX should feel shorter/clearer).
- Users can find something by name, SKU, code, or by walking **Locations**.
- Supplies remain understandable; equipment is either clearly in-scope with real data or explicitly deferred (no fake asset UI).
- Users can **Receive**, **Record usage/waste**, **Transfer**, and **Count** without confusing new jargon unless renaming is deliberate and complete.
- Scan/label flows work end to end; unsupported features are not shown as active.
- History remains trustworthy (ledger integrity preserved).
- Responsive and accessible on desktop and mobile; design-system rules held.
- Any Spring Boot changes are justified, tested, migration-aware, and preserve tenant isolation and stock concurrency invariants.

---

## 10. Instructions to the coding agent

1. **Audit the repository first** against §2. This brief is direction, not proof that screens or models are missing.
2. Be critical of current UX (tables-only browse, weak breadcrumbs, long create forms, etc.) while respecting working stock/scan/request pipelines.
3. Take the strongest ideas from Sortly, Shelf, and BoxHero; propose a cohesive **Backroom** experience — not a clone, not Sortly’s red chrome.
4. If a better UX needs backend work, weigh smallest safe Spring Boot change, benefit, alternatives, and risks. Recommend; don’t auto-refuse or blindly rebuild the schema.
5. Start by returning: findings, proposed flows/screens, feature↔backend matrix, and prioritized sequence. Then implement approved slices with tests and migrations where applicable.
6. Prefer extending `docs/design-system.md` / `docs/sortly-inspiration.md` over inventing conflicting visual rules.
