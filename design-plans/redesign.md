# Redesign handoff · Make the next action visible

Applies to the Backroom React frontend at `bab50df` plus its current inventory and location layout changes. The underlying room, shelf, item, placement and movement records remain the source of truth. The priority is to shorten frequent work while keeping the context needed to change stock correctly.

## 1. Start stock work from the item or the shelf

**Current → target.** Known shelf: **Update → Record usage → quantity** becomes **Record usage → quantity**. Item-only lookup: use the same **Record usage** action after a catalog lookup or an item scan. The source shelf is a data decision only when more than one shelf can supply the item.

- On an item’s **Stock by location** rows and a location’s **Expected items** rows, show **Record usage** for positive stock. Name the secondary button **More actions** with the item or shelf in its accessible name. On zero-stock active rows use **Receive stock** for the direct action. Keep archived records read-only. Wrap controls under the row text on narrow phones so long names and quantities remain visible.
- In the active item header expose **Record usage** when stock is positive, alongside **Receive stock**. Keep **Request more** and the manager/owner **Transfer** action available. Reuse the same form for scan, shelf and item entry. Secondary actions retain waste, receiving, restocking and existing manager-only capabilities.
- Open StockDialog directly. With a known eligible shelf, show the shelf and available quantity as labeled context and focus **Quantity**. From an item-only entry with one eligible shelf, do the same. With several eligible shelves and no known source, start **From** at **Choose a shelf**, focus that field and require one selection. A stale or depleted known shelf prompts a new choice. Never silently debit another shelf.
- Keep the per-dialog operation key on retry. Submit the selected location and quantity through the existing removal endpoint. Use the matching returned movement’s `balanceAfter` and unit for the status, for example **Used 1 kg from Coffee shelf. 11 kg on hand there.** Refresh the affected rows. An insufficient-stock response keeps the form and typed quantity visible; closing returns focus to the initiating control. [Stock form](../frontend/src/stock.tsx) · [Response types](../frontend/src/types.ts)

## 2. Give each role a predictable Home

**Current → target.** Reopening a business now lands in Inventory; a returning owner then opens Home and scrolls. Send ordinary business entry to Home. Preserve explicit destinations from QR labels, invitations, reset flows and sign-in redirects.

| Role | First screen | Next action |
|---|---|---|
| Employee | Search by item, SKU, location or code; **Scan** next to search. Follow with **To receive** and **My requests**. | An item result opens its stock actions. A delivery waiting for receipt opens the existing request. The employee never sees review controls. |
| Owner or manager with operational data | Put **Needs review** and **Low stock** counts above setup and recent activity. On desktop place the two work lists side by side. On phone show both count links before either list expands. Start with pending decisions. | Pending row → request detail in **1** action. **Needs review** count → filtered list → request in **2**. Low-stock rows retain shelf, minimum, shortfall and the existing request action. |
| New owner | With no inventory movements and no pending decisions, expand setup. Once either exists, collapse the checklist into **Continue setup · n remaining** below the work counts. | Expand and collapse reversibly. Keep unfinished setup reachable, including for browsers with the existing hidden flag. |

Use the existing five destinations **Home, Inventory, Scan, Requests, Locations**. They already represent distinct jobs. Keep **People** and **Business settings** owner-only, with **Activity** and **Export data** also available to managers. Use the existing counts, low-stock list, role filters and request statuses. On a summary read failure show the current error/retry treatment; a missing count must not render as zero. Use a single primary action per view.

Change the setup label task to **Download a label**. Open the existing Locations selection mode with selectable targets; after a selection continue to **Print 1 label** and **Download PDF**. Persist the organization-scoped completion flag from a successful download. Use a new flag distinct from the legacy click-only flag since past clicks do not establish that a PDF was generated. If no location exists, send the owner to add one first. Align invitation copy with actual permissions: **Employee — find items, record stock changes and request supplies**; **Manager — manage inventory and locations, review requests and oversee stock**. [Home and onboarding](../frontend/src/home.tsx) · [Role picker](../frontend/src/people.tsx)

## 3. Carry context across searches, shelves and requests

**Current → target.** Filtered inventory → item → shelf currently offers the parent room, then requires retracing the search. Show **Back to House espresso** on that shelf and a separate **In Stockroom** hierarchy link. Returning from the item restores the catalog results that opened it.

- Encode applied inventory state in URL parameters: `q`, the existing `location` and `low`, plus `archived` and the zero-based `page`. Draft search text stays local until submitted; changing an applied filter resets the page. Existing bare URLs retain their defaults. Preserve global search’s existing `q` and Requests’ existing `filter`.
- Pass a typed, same-organization origin through record links using router history state: source route and query, friendly return label, clicked record ID, and prior scroll position. Carry the immediate origin through nested detail links. Use a verified prior history entry when available; fall back to the saved internal URL, then canonical Inventory or Locations for direct links. Restore focus to the originating row after records load. If that row has disappeared, use the results heading. Validate the organization and allowed route at use time.
- Keep hierarchy separate from return navigation. A direct shelf QR with no origin offers **All locations** and, where available, the parent location. When opened from a recognized scan result, **Back to scan result** restores the resolved result in session history with the camera paused. Business switching and sign-out clear transient origin/result state.
- Implement **See stock history** as an item link with a History anchor. After item data loads, scroll and focus the History heading below the sticky header. Preserve the request as origin. A normal item link continues opening at its title. [Search](../frontend/src/search.tsx) · [Locations](../frontend/src/locations.tsx) · [Request detail](../frontend/src/requests.tsx)

**API and compatibility.** These changes use the existing inventory queries, scan resolution, request summary/list, label PDF download and stock operation response. No backend endpoint, database migration or permission change is needed. Keep the existing public QR paths and browser Back/Forward behaviors. Everything needed for the new inventory URL and origin state lives in the frontend.

## Acceptance checks for implementation

These are targets for the redesigned app. The [audit](README.md) records the current measurements separately.

| Check | Pass condition |
|---|---|
| Known-stock row, each role | From either item or shelf, one **Record usage** activation opens the quantity form. Verify item, shelf, unit and initial focus. Use 1 of 12 kg; notice and refreshed shelf row show 11 kg. |
| Unknown source, varying availability | With one stocked shelf no choice is required. With two, no default is committed; explicitly select one, submit once and verify only that shelf changes. Zero-stock rows offer receiving. Insufficient stock preserves input; retry uses the original operation key. |
| Owner oversight and employee start | At 390 × 844 both owner counts are visible without scrolling in an established business. An awaiting-review request is reachable in at most two actions. A new owner can complete setup. An employee sees scanning, finding and receiving without approval or admin actions. |
| Label and permissions | Opening setup, selecting a shelf, cancelling or failing a download never marks the label task complete. A successful PDF download does. Scan the generated QR signed out, sign in and reach the original record. Verify transfer and review remain restricted server-side. |
| Search, scan and hierarchy | From page two of filtered Inventory, go item → shelf → item → results. Query, filters, page, scroll and focused row survive. Repeat from global search and a scan. Verify browser Back, Forward, refresh, an orphaned origin and business switch, plus the separate parent-room link. |
| Partial delivery and history | Request 5 kg, approve 4 kg, receive 1.5 kg. Detail shows 5, 4, 1.5 and 2.5 kg under their respective labels. **See stock history** makes the heading visible and focused; returning restores the request. |
| Input and layout | Exercise the primary paths as both roles at 390 and 1280 px, plus 320 px, browser zoom, long names/SKUs and decimal units. Check touch targets, keyboard-only navigation, dialog trapping and focus return, announced errors, loading, zero results and retry. Include a screen-reader pass on the new stock status. |

Implement and validate in this order: direct stock actions, role-specific Home and setup, then contextual navigation. Extend the existing browser scenarios for each changed path; run the frontend build and full Playwright suite. For a human usability check, give a first-shift employee the task **“Use 1 kg from the coffee shelf”** and an owner **“Find the request needing approval and approve 4 kg.”** Record the first control chosen, incorrect shelf selections, requests for help and successful completion. Compare against the baseline using the same fixtures. No completion-time improvement has been measured yet.
