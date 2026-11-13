# Backroom — UI/UX overhaul & backend opportunity review

> **Purpose:** Improve UX on top of what already ships, and decide whether further backend work is justified. This is a product and implementation brief for an **evolving product**, not a greenfield rebuild and not a redo of work already landed.

## Status (November 13, 2026): delivered

This brief has been carried out. Read the rest as the record of what was asked, not as open work. Where a section below says something is "absent", "still open" or "teal", this table is the current truth.

| Brief item | Outcome | Where |
| --- | --- | --- |
| Visual tiles, Grid/List toggle, location breadcrumbs, short add (optional SKU) | Shipped | [Phase 6](phase-6.md) |
| Item photos | Shipped (JPEG in Postgres, 600 KB cap, cached by version) | [Phase 6](phase-6.md) |
| **Sold** removal reason | Shipped as **Record sale**; no prices kept | [Phase 6](phase-6.md) |
| Home space entry | Shipped: **Spaces** folder cards and metric cards | [Phase 6](phase-6.md), [design system](design-system.md#home-and-low-stock-patterns) |
| Import / bulk upload | Shipped: CSV import with review, duplicates and idempotent apply | [Phase 7](phase-7.md) |
| Sortly-style explorer | Shipped: folder cards, summary strip, Show folders switch, item sort (`?sort=`), dashboard Home, item hero with circular actions and metric grid | [Design system 3.1](design-system.md#folder-explorer-patterns) |
| Visual identity | **Changed:** coral accent and coral rail replace the teal, gray-chrome rule in §2 and §6 | [Design system](design-system.md#color), [Sortly inspiration](sortly-inspiration.md) |
| Equipment / assets, custody | Deferred honestly; no fake asset UI | [Architecture](architecture.md) |
| Location photos, tags, custom fields, suppliers/POs/POS, email recovery | Not built | — |

The role-based Home, direct stock row actions, shelf-choice rules, contextual back, **Download a label** and scan confirm-before-mutate from §2 all survived and are covered by the browser suite. The 3.1 audit found muted text on the gray canvas at 4.4∶1, under AA; `--text-muted` was darkened to `#636975` (5.1∶1). One follow-up remains: `design-specimen.png` still shows the pre-coral UI.

**Baseline commit context:** Phases 1–5 plus the frontend UX follow-up in `9d99249` (*direct stock actions, role-based Home and return navigation*). Re-read the live tree before proposing changes — do not treat older screenshots or pre-follow-up docs as current UI.

**Read first (source of truth in this repo):**

- [README](../README.md) — product summary and setup
- [Architecture](architecture.md) — modular monolith; future `assets` package not built
- [Design system](design-system.md) + [frontend codebook](frontend-codebook.md) — tokens and UI rules **including** post-follow-up patterns
- [Sortly inspiration](sortly-inspiration.md) — browse/UX direction (much of this is **still guidance**, not UI)
- [UX audit & redesign](../design-plans/README.md) · [journeys](../design-plans/journeys.md) · [redesign](../design-plans/redesign.md) — **shipped** FE work; do not re-open as open P1s
- Phase contracts: [1](phase-1.md) · [2](phase-2.md) · [3](phase-3.md) · [4](phase-4.md) · [5](phase-5.md) (includes follow-up section)

---

## 1. Product direction

Backroom is a lightweight operations system for small businesses that need to **organize, find, use, move, and maintain the physical things they rely on**.

- **Primary use case:** Internal supplies, consumables, tools, equipment, and storage spaces.
- **Secondary use case:** Items may occasionally be sold. Today stock-outs are **Usage** or **Waste** only. A `Sold` (or similar) reason is a *possible extension* — do not invent POS, storefronts, accounting, or order management.
- **Core idea:** Help an owner or employee answer: *What do we have? Where is it? How much is left? Who changed it? Does anything need attention?*
- **Target users:** Cafés, workshops, repair shops, studios, small venues. Avoid warehouse/retail-only assumptions.
- **Non-negotiable technology:** **Java + Spring Boot stays the backend.** Prefer work inside the existing modular monolith.

### Product principles

1. **Physical spaces first.** Locations already nest; make walking them feel natural (breadcrumbs, visual browse — still open).
2. **Simple by default; powerful when needed.** Daily stock actions are already one tap on rows; keep advanced under **More actions**.
3. **Make daily actions obvious.** Prefer existing verbs: Receive, Record usage, Record waste, Transfer, Count, Scan.
4. **A clear record of change.** Ledger + activity exist; keep history trustworthy and easy to reach.
5. **Work across devices.** Sidebar + bottom tabs; role-appropriate Home; contextual back.
6. **Avoid enterprise clutter.** No fake asset/POS UI. Progressive disclosure.

---

## 2. Current product reality (baseline — do not rediscover)

Phases **1–5** and the **design-plans FE follow-up** are implemented. Treat the tables below as **current truth**, not wishlist.

### Stack

| Layer | Reality |
| --- | --- |
| Backend | Java 21, Spring Boot modular monolith (`com.backroom.*`), PostgreSQL 17, Flyway, JPA validate-only |
| Frontend | React 19 + TypeScript + Vite; plain CSS in `styles.css` / `workspace.css`; shared components in `components.tsx` |
| Auth | Email/password, session + CSRF; roles `OWNER` / `MANAGER` / `EMPLOYEE`; org in URL |
| Packaging | SPA in the same JAR |

### Navigation that already ships

Primary tabs (all roles) — `frontend/src/workspace.tsx`:

| Label | Route | Job today |
| --- | --- | --- |
| **Home** | `home` | **Role-split** entry (see below) |
| **Inventory** | `items` | Catalog; URL filters `q`, `location`, `low`, `archived`, `page` |
| **Scan** | `scan` | Camera + manual entry → open or stock sheets (never silent mutate) |
| **Requests** | `requests` | Internal restock (not supplier POs) |
| **Locations** | `locations` | Nested Room / Shelf / Cabinet / Bin |

Administration: Activity + Export (Owner/Manager); People + Business settings (Owner).

Also routed: detail pages, labels, search. Public QR: `/s/{token}`. Opening a business from the chooser lands on **Home**.

### Home is role-based (shipped)

| Role | First content | Then |
| --- | --- | --- |
| **Owner / Manager** | Boxed **Needs review** + **Low stock** count tiles | Search + secondary Scan; Requests + Low stock cards; Request stock; setup (collapses once there is work); Recent activity |
| **Employee** | Search + primary **Scan** | **To receive** tile; My requests + Low stock; no setup/activity/admin |

Setup checklist: Expand/Collapse (not Hide); label step is **Download a label** (ticks only after PDF download). Invitation role copy matches real stock permissions.

### Direct stock actions (shipped — `Update` is gone)

On item **Stock by location** and location **Expected items** rows (`StockRowActions`):

| Condition | Primary button | Secondary |
| --- | --- | --- |
| Quantity > 0 | **Record usage** | **More actions** → role-filtered sheet |
| Quantity = 0 | **Receive stock** | **More actions** |

Item header: **Record usage** (primary when stocked), **Receive stock**, **Request more**, manager **Transfer**. Edit/Archive live under **Manage item** at the page end.

**Choosing the shelf:** Usage/waste never guess. Known row / scan-with-shelf / sole stocked shelf → show path + available qty and focus Quantity. Several shelves and unknown source → **Choose a shelf**. Success copy includes balance after: e.g. “Used 2 kg from Stockroom › Shelf A. 10 kg on hand there.”

### Contextual return navigation (shipped)

`frontend/src/navigation.tsx`:

- `RecordLink` / visit stash origin (URL, label, scroll, scan state).
- Back reads **‹ Back to {label}** and restores filters, page, scroll, focus; else fallback Inventory / Requests / All locations.
- Parent location is a **separate** hierarchy link (“In Stockroom”), not the back link.
- **See stock history** focuses the item’s History heading.
- Scan `?action=` visits preserve return to the scan result.

### Domain that already exists

| Area | Reality |
| --- | --- |
| Organizations | Multi-business; switch |
| People | Invite Manager/Employee; fixed three roles; owner-issued password-reset links (no email) |
| Locations | Nested `parentId`; ROOM/SHELF/CABINET/BIN; archive; leaf placements |
| Items | Name, optional SKU, unit; archive; on-hand; low |
| Placements | Item ↔ leaf; optional **minimum** |
| Stock ledger | Immutable ops/movements; reversals; no direct balance edit |
| Stock ops | Opening, Receive, Usage, Waste, Transfer, Count, Reverse |
| Codes / labels | Generated QR + manufacturer barcodes; PDF layouts; Scan tab |
| Restock | Request → approve/reject → deliveries; close short |
| Search | Items, locations, code match |
| Exports | CSV snapshots / ranged history |
| Audit | Owner/Manager activity |

### Explicitly **not** in the product (still gaps)

- Item/location **photos** / media storage
- **Grid / list** browse toggle and photo-first cards (Sortly doc only)
- Location **breadcrumbs** trail (contextual back ≠ space path walk)
- Categories, tags, custom fields
- Separate **Equipment / assets** entity (`assets` package planned only)
- Checkout / assignment / custody
- **Sold** removal reason (USAGE \| WASTE only)
- Suppliers, POs, pricing, POS
- Import / bulk upload
- Soft-restore archives; self-service email password recovery

### Stock language (keep unless renaming is deliberate)

| Op | UI label |
| --- | --- |
| RECEIPT | **Receive stock** |
| USAGE | **Record usage** |
| WASTE | **Record waste** |
| TRANSFER | **Transfer stock** |
| COUNT | **Count stock** |
| OPENING | **Set starting quantity** |
| REVERSAL | **Reverse** |

### Design system already in force

- Neutral canvas + **one teal accent**; system fonts; border panels; phone sheets.
- Documents role-based Home, stock rows, shelf choice, contextual back — **evolve in place**.
- Do not invent a parallel design system or Sortly-red chrome.

### Already completed UX audit (do not re-litigate)

[design-plans/](../design-plans/README.md) findings (Update menu, wrong-shelf default, Home below-the-fold decisions, label checklist, lost search context, history focus, invitation copy) were implemented in the FE follow-up. **Next overhaul work starts after that baseline**, primarily Sortly-style browse and remaining gaps — not another “make Record usage visible” pass.

---

## 3. Competitor references: borrow patterns, not visual identity

### Sortly — primary remaining UI inspiration

Reference: https://www.sortly.com/ · [sortly-inspiration.md](sortly-inspiration.md)

Still the north star for **visual browsing** (not for stock verbs or Home — those are settled):

- Photo-first item cards; recognizable **space** navigation
- Grid/list switching; search/filters; **breadcrumbs** for physical hierarchy
- Focused item detail (qty, places, status, history) — detail actions largely done; browse list still table-first
- Short add-item flows (name + unit; optional SKU)
- Mobile quick actions without stretched-phone desktop

**Adaptation:** Physical spaces, actionable stock, attention — already strong. Borrow Sortly’s *see and walk* clarity; keep Backroom tokens and shipped stock/Home patterns.

### Shelf — domain modeling

Reference: https://www.shelf.nu/

- Assets vs quantity stock — **still an opportunity review** (no assets module)
- Nested locations + QR → correct record — **shipped**
- Custody/condition/maintenance — **absent**; justify or defer

### BoxHero — transactions & onboarding

Reference: https://www.boxhero.io/

- Stock in/out/move/count + history — **shipped** with Backroom verbs
- Scan + create — **shipped**; import — **absent**
- Onboarding — **shipped** (role-aware Home + Download a label)

---

## 4. Target information architecture (evolve)

| Concept | Current surface | Guidance |
| --- | --- | --- |
| Overview | **Home** (role-split) | Polish attention; don’t add vanity KPIs or undo role layouts |
| Spaces | **Locations** | Add walkable browse (cards, breadcrumbs); keep types + nesting |
| Inventory | **Inventory** | Photo/grid browse on top of filters already in the URL |
| Equipment | *Missing* | Only with a real `assets` (or dual-mode) model; else defer honestly |
| Activity | Activity + item History | Contextual history link already focuses History |
| Scan | Scan + `/s/{token}` | Preserve confirm-before-mutate; improve only if evidence requires |
| Requests | Requests | Keep internal restock ≠ purchasing |
| Settings / People / Exports | Admin destinations | As today |

Do not rename tabs without journey evidence. Do not split Inventory/Equipment without a data model decision.

---

## 5. Priority screens — what’s done vs still open

### A. Home

**Done:** Role-split layouts; Needs review / Low stock / To receive tiles; setup collapse; Download a label; chooser → Home.

**Still open:** Stronger space entry points; any Sortly-like visual cues without clutter; don’t reintroduce a single shared Home.

### B. Locations / spaces

**Done:** Nested browse; expected items with direct stock actions; codes; parent link separate from back.

**Still open:** Breadcrumb trail of the space path; card/grid space browsing; search-within-space if still weak; optional space photos later.

### C. Inventory / item detail

**Done:** Filters in URL; Low badges; direct row actions; header action order; Manage item; shelf choice rules; history + focus from requests.

**Still open:** Photo/visual tiles; grid/list toggle; shorter create (optional SKU disclosure); denser recognition without losing table mode.

### D. Equipment / assets

**Not shipped.** Opportunity only. No Checked out / Assign badges without data.

### E. Scan & transactions

**Done:** Camera + manual; deep links; operation keys; role gates; return to scan result; no silent stock change.

**Still open:** Incremental speed/clarity only if measured; camera-denied fallback polish.

### F. Onboarding

**Done:** Checklist; Expand/Collapse; Download a label after PDF; accurate invite copy.

**Still open:** Further shortening first create; never require categories/suppliers (don’t add them for onboarding).

---

## 6. Visual design direction

Align with [design-system.md](design-system.md) + [sortly-inspiration.md](sortly-inspiration.md):

- Calm warm-neutral + teal act/here only
- Photo-aware **cards** for browse; keep list/table density
- Extend existing components (`browse` patterns, breadcrumbs) — don’t fork
- Preserve role-based Home tiles and stock-row action layout
- Badges only for real states (Low, Archived, request statuses, Reversed)
- A11y: focus return, 44px targets, labeled controls — don’t regress `navigation.tsx` behavior
- No decorative dashboards/charts; no Sortly-red shell

---

## 7. Backend opportunity review — required

Audit APIs, Flyway, services, auth, and stock concurrency before deciding. For each capability: supported now? FE-only? backend opportunity? trade-offs? recommendation?

| Capability | Current reality | Weigh |
| --- | --- | --- |
| Nested locations | **Exists** | Breadcrumbs/cards are FE |
| Direct stock UX | **FE done** on existing removal/receipt APIs | Don’t churn endpoints for verbs |
| Return navigation | **FE done** (`navigation.tsx`) | No API needed |
| Consumables vs assets | Single item model; `assets` future | New module vs defer |
| Stock ledger | Immutable ops + reversals | Presentation only unless new event types |
| Transfers | Atomic dual movements | Multi-item moves? |
| QR / barcodes | Generated + GTIN + deep links | More code kinds? |
| Assignment / custody | **Absent** | Lightweight history vs defer |
| Low stock | Per-placement minimums; Home tiles | Email alerts absent |
| Multi-business | Org-scoped + roles | Never break isolation |
| Restock | Internal requests | Don’t rename into purchasing |
| Import / bulk | **Absent** (export exists) | Defer vs onboarding |
| Photos | **Absent** (no object storage) | Large cost — justify or defer |
| Sold / extra reasons | USAGE \| WASTE only | Small enum vs POS creep |
| Grid/list + tiles | FE-only without photos | Prefer FE first |

**Rules:** Favor backend when integrity or a real domain concept demands it; favor FE when APIs already work (most remaining Sortly browse work); defer ERP/POS; stay in Spring Boot module boundaries; migrations + tests when changing schema; don’t invent absent features.

---

## 8. Execution plan

### Phase A — Inspect

Confirm the live UI matches §2 (especially Home by role, `StockRowActions`, `navigation.tsx`). Diff against [design-plans/](../design-plans/) so you don’t re-file shipped bugs. Note drift in docs vs code.

### Phase B — Propose

1. Screen plan **anchored to current screens**, marking Done vs Open (§5).
2. IA deltas only where evidence requires.
3. Design-system **deltas** (tiles, grid/list, breadcrumbs) — not a rewrite.
4. Flows using **current** stock verbs and return navigation.
5. FE↔API matrix for remaining gaps only.
6. Backend opportunities (photos, assets, Sold, import) with deferrals.
7. Phased plan + acceptance criteria.

Separate **quick wins** (grid/list, visual tiles without upload, breadcrumbs, short add) from **platform** work (media, assets module).

### Phase C — Implement

Preserve Phases 1–5 + follow-up behavior. Suggested order:

1. Browse patterns: visual tiles, grid/list, location breadcrumbs (FE).
2. Inventory/Locations list polish; short add-item.
3. Home/space entry polish without undoing role layouts.
4. Scan/onboarding/a11y polish as needed.
5. Approved backend opportunities last (migrations + tests).

Avoid visual rewrites that break stock rows, More actions, or contextual back.

---

## 9. Definition of done

- No regression: role-based Home, direct row stock actions, shelf-choice rules, contextual back, Download a label, scan confirm-before-mutate.
- Users can find by name/SKU/code or by walking Locations (improved browse if in scope).
- Equipment either real or explicitly deferred — never fake.
- Stock verbs stay consistent unless a deliberate rename lands everywhere.
- History and tenant/stock invariants intact.
- Responsive + accessible; design-system rules held.
- Spring Boot changes justified, tested, migration-aware.

---

## 10. Instructions to the coding agent

1. **Audit against §2 and `design-plans/` first.** Shipped follow-up work is baseline, not a backlog.
2. Be critical of remaining gaps (tables-only browse, no photos, no space breadcrumbs, long create forms) without reopening fixed P1s from the audit.
3. Take Sortly/Shelf/BoxHero ideas into a cohesive Backroom experience — not a clone.
4. Backend changes: smallest safe Spring Boot change, benefit, alternatives, risks — then recommend.
5. Return findings, flows, FE↔API matrix, and sequence before large implementation.
6. Extend [design-system.md](design-system.md) / [sortly-inspiration.md](sortly-inspiration.md); don’t contradict them or reintroduce **Update** / single Home / Hide-only setup.
