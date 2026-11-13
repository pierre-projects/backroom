# Sortly → Backroom: design system & UX rules

Primary UI/UX inspiration for Backroom inventory browsing.

**Reference:** [sortly.com](https://www.sortly.com/) · Help: [Product overview](https://help.sortly.com/sortly-product-overview), [Item locations](https://help.sortly.com/view-item-locations-in-sortly), [Photos](https://www.sortly.com/features/photos/)

**Scope of this paper:** Observed Sortly product patterns and marketing UI signals, turned into rules Backroom can follow. Not an official Sortly brand system. Not implementation.

**Backroom adaptation (north star):** Keep Sortly’s clarity and approachable visual browsing, but prioritize **physical spaces**, **actionable stock changes**, and **things needing attention**. Keep Backroom’s quiet teal/neutral chrome ([design system](design-system.md)); do not adopt Sortly’s red product chrome.

---

## 1. What Sortly optimizes for

Sortly is inventory that people *recognize* and *touch*, not a spreadsheet with a skin.

| Sortly emphasis | Meaning for Backroom |
| --- | --- |
| See it to find it | Items and spaces should be visually scannable before you read metadata |
| Folders as places | Hierarchy is how you navigate (“where am I?”), not only how you filter |
| Light cognitive load | Short flows; advanced fields optional |
| Same product, two layouts | Mobile for quick actions; desktop for overview — not a stretched phone |

Backroom already owns records, roles, stock history, and labels. Sortly supplies the **browse and recognize** layer on top of that.

---

## 2. Information architecture (observed)

### Sortly product map

| Area | Role |
| --- | --- |
| **Dashboard** | Summary counts; items that need restocking; recent items/folders; recent activity |
| **Items & Folders** | Primary workspace: nested folders beside/above content; items inside the current folder |
| **Search** | Quick keyword search everywhere; advanced filters (folder, name, quantity, ID, custom fields) |
| **Tags** | Cross-cutting groups (brand, color) that are not the folder tree |
| **Reports** | History and summaries for audits |
| **Settings** | Profile, access, display, company |

### Backroom mapping

| Sortly | Backroom |
| --- | --- |
| Dashboard | **Home** — lean harder into attention (low stock, open requests), not vanity totals |
| Folders | **Locations** (rooms → shelves → bins) — physical spaces, not abstract folders |
| Items | **Inventory** catalog + item detail |
| Tags | Optional later; do not invent a parallel taxonomy that fights locations |
| Quick search | Home + sidebar search; inventory/location toolbars |
| Advanced filters | Location, low stock, archived — keep few and named |
| Item photos | Photo-first *layout* now; real photos when the product supports them |

**Rule:** One primary hierarchy — **space**. Everything else (search, low stock, archived) is a filter on that world, not a second tree.

---

## 3. Visual design signals (Sortly)

Observed from the public site and product screenshots (not recovered source tokens).

### Color

| Role | Sortly signal | Backroom rule |
| --- | --- | --- |
| Brand / primary CTA | Strong red | Keep **`--accent` teal** — one accent, one meaning |
| Canvas | White / near-white | Keep `--bg` / `--surface` |
| Chrome | Red sidebar in product; white content | Keep gray/white chrome; accent only for act/here |
| Feedback | Clear success / warning / alert | Keep success / warning / danger soft pairs |

**Do not** repaint Backroom red to “look like Sortly.” Borrow structure and browsing, not the brand color.

### Typography & density

- Clean sans UI type; bold names, quieter metadata.
- Generous whitespace in browse views; denser only in history/tables.
- Sentence-case labels; no slogan voice inside the app (already a Backroom rule).

### Shape & depth

- Soft rounded cards and controls; clear borders more than heavy shadows.
- Product UI feels consumer-approachable, not ERP-dense.
- Backroom: keep existing radii and border-first panels; cards only where they aid browsing or action (see §5).

### Imagery

- Photos dominate item and folder recognition (up to 8 per item/folder in Sortly).
- Thumbnails in list rows; large tiles in grid.
- Until Backroom has photos: use **visual tiles** (photo slot / initials / space icon) so the layout stays photo-first and photos can drop in later.

---

## 4. UX principles (Sortly → Backroom)

### P1 — Photo-first recognition

**Sortly:** Find the right PVC pipe or tool by looking, then confirm name/qty.

**Backroom rules:**

1. Every browse row/card has a leading **visual** (photo or reserved tile), then name, then qty/status.
2. Variants that look different must be distinguishable without reading SKU alone.
3. Grid view is the recognition mode; list view is the comparison mode.
4. Do not bury the only visual behind a detail page.

### P2 — Spaces you can walk

**Sortly:** Folders / nested folders; breadcrumbs (`Inventory › All › Hand Tools`); folders listed with items.

**Backroom rules:**

1. Locations are **spaces**, not labels: Room → Shelf → Bin.
2. Always show **where you are** with breadcrumbs (or equivalent trail), not only a Back link.
3. Show **parent › child** on every stock row and picker so two “Shelf A”s never collide ([existing decision](design-system.md#research-behind-the-decisions)).
4. Opening a space shows **child spaces first**, then items kept here — same mental model as Sortly folders-then-items.
5. Prefer browsing by space for stock work; catalog-wide search is for when you don’t know where it lives.

### P3 — Easy grid / list switching

**Sortly:** Toolbar control to flip layout; same filters and search in both.

**Backroom rules:**

1. Inventory (and location contents) support **Grid** and **List** with one control.
2. Filters, search, and selection mode survive the switch.
3. Remember the last choice per surface (per browser).
4. Grid: tile + name + on-hand + attention badge. List: compact row with same facts, better for long scans and desktop density.
5. Desktop must feel like a **wide browse**, not a phone UI scaled up (more columns, persistent trail, actions in the header — not a single stacked column only).

### P4 — Search, filters, breadcrumbs

**Sortly:** Search All Items; advanced filters; folder path context.

**Backroom rules:**

1. Search is always nearby (toolbar or Home); placeholder names the fields (name / SKU).
2. Filters stay few: **location**, **low stock**, **include archived**.
3. Active filters are visible and easy to clear (empty states offer Clear).
4. Breadcrumbs answer “where am I?”; search answers “where is it?”.
5. Results that span spaces still show path context.

### P5 — Focused item detail

**Sortly:** Detail shows photo, quantity, location, identity fields, history-adjacent actions.

**Backroom rules — item page job order:**

1. **Recognize** — visual + name + status (Active / Archived / Low).
2. **Know stock** — on hand total, then **stock by location** with parent path.
3. **Act** — Receive, Use, Count, Transfer, Request — named verbs, thumb-reachable.
4. **History** — newest first; reverse when allowed.
5. Codes/labels and admin edit/archive sit after primary stock work.

Do not lead with SKU grids or settings. Lead with quantity, place, and what to do next.

### P6 — Short add flows; advanced optional

**Sortly:** Add Item with core fields; photos/custom fields available without blocking create.

**Backroom rules:**

1. **Required to create:** name + base unit (unit is how Backroom counts).
2. **Optional on create:** SKU (and later photos, notes, custom fields) behind “Optional details” or a second step.
3. After create, land on item detail ready to **Receive stock** or set a place — don’t dump into a long form.
4. Editing can expose everything; creating should not.

### P7 — Mobile quick actions; desktop overview

**Sortly:** Strong mobile app story; desktop for organizing and seeing the whole tree.

**Backroom rules:**

| | Phone | Desktop |
| --- | --- | --- |
| Nav | Bottom tabs | Sidebar |
| Primary actions | Full-width / bottom sheet | Header actions + keyboard-friendly |
| Browse | Prefer grid; large targets | Grid or list; multi-column spaces |
| Stock update | Sheet with one job | Same sheets; more context visible behind |
| Don’t | Shrink desktop chrome into a tall phone column with no trail | Enlarge phone components without using width for spaces + list |

**Rule:** Shared components and tokens; different **density and chrome**, not different products.

### P8 — Attention over decoration

**Sortly Dashboard:** “Items that need restocking” sits with summary and recent activity.

**Backroom rules (adaptation priority):**

1. Home and inventory must surface **low stock** and **open requests** before decorative summary.
2. Attention uses **warning tone + words** (“Low”), never color alone.
3. From an attention row, one tap reaches **Request**, **Receive**, or item detail — not a dead end.
4. Counts in nav (e.g. Requests badge) stay meaningful, not vanity metrics.

---

## 5. Component patterns to borrow

| Pattern | Sortly behavior | Backroom rule |
| --- | --- | --- |
| **Item card** | Photo, name, qty (and often price/folder) | Photo/tile, name, on-hand + unit, Low/Archived badge; no price-first |
| **Space / folder card** | Image or folder cue + name | Type badge (Room/Shelf/…) + name; enter to drill down |
| **List row** | Small thumb + name + key facts | Same; chevron for navigation; Update for in-place stock when inside a space |
| **View toggle** | Grid / list icons in toolbar | Same; `aria-pressed`; label the group |
| **Breadcrumb** | Path above content | Locations trail; Inventory root on catalog |
| **Toolbar** | Search + filters + add + view | One toolbar band; primary Add stays teal once |
| **Detail hero** | Large photo + facts | Visual + on hand / SKU / unit / status |
| **Add sheet** | Short create | Name + unit; optional SKU collapsed |
| **Attention list** | Need restocking | Low stock + request status side by side |

**Cards:** Allowed for browse recognition and attention lists. Not for every block of text. Prefer border panels for history and forms (existing Backroom depth rules).

---

## 6. Interaction & motion

| Topic | Rule |
| --- | --- |
| Feedback | After stock change, say what happened and where (`Received 10 L at Stockroom › Shelf A`) |
| Sheets | One task per dialog; Cancel safe; primary verb matches title |
| Motion | Short (existing ~120–160 ms); no motion theater; honor `prefers-reduced-motion` |
| Selection | Label print mode overlays browse without changing hierarchy |
| Empty states | Name the next action (Add item / Clear search / Show all locations) |

---

## 7. Do’s and don’ts

### Do

- Design browse surfaces as **visual first**, metadata second.
- Treat **locations as the folder system**.
- Keep **grid ↔ list** one tap away.
- Put **qty, place, status, history** at the center of item detail.
- Keep create **short**; park advanced fields.
- Make **attention** (low stock, requests) a first-class Home/Inventory job.
- Stay inside Backroom tokens, type scale, and accessibility rules.

### Don’t

- Copy Sortly’s red UI or marketing hero patterns into the app shell.
- Replace physical locations with abstract “folders” that aren’t places.
- Ship grid without list (or vice versa) as the only mode.
- Make desktop a zoomed phone (no breadcrumbs, single column only, no overview).
- Force SKU/photos/custom fields before the first save.
- Hide low stock behind reports only.
- Use cards as decoration for static copy.

---

## 8. Screen checklist

Status after [Phase 6](phase-6.md). Real item photos shipped; location photos are deferred.

Use this when designing or reviewing a surface — not a build ticket list.

### Inventory

- [x] Visual tile/photo on every item  
- [x] Grid and list toggle  
- [x] Search + location + low + archived  
- [x] Low items visually marked  
- [x] Add item: name + unit; optional SKU  

### Locations / spaces

- [x] Breadcrumb trail  
- [x] Child spaces then items (rooms; shelves and bins lead with their items, since that is where stock lives)  
- [x] Parent name on ambiguous shelves  
- [x] Update stock from inside a space  

### Item detail

- [x] Hero: visual, on hand, status  
- [x] Stock by location with paths  
- [x] Primary stock actions obvious  
- [x] History after stock  

### Home

- [x] Low stock and requests before quieter activity  
- [x] Search available  
- [x] Clear next action from each attention row  

### Responsive

- [x] Phone: tabs, sheets, large targets  
- [x] Desktop: sidebar, trail, multi-column browse  

---

## 9. Relationship to the existing Backroom system

| Keep from Backroom | Take from Sortly |
| --- | --- |
| Teal accent, neutral canvas, AA contrast | Photo-first browse |
| Bottom tabs + sidebar shell | Folder/space navigation + breadcrumbs |
| Verb+noun copy, stock message patterns | Grid/list switching |
| Parent › child location labels | Short add + optional advanced |
| Roles and permissions | Attention-led Home |

When rules conflict: **Backroom tokens, accessibility, and stock truth win**. Sortly wins on **browse clarity and approachability**.

---

## 10. Sources & limits

| Source | Use |
| --- | --- |
| [sortly.com](https://www.sortly.com/) | Marketing + product UI screenshots (photo-first cards, mobile/desktop pairing) |
| [Product overview](https://help.sortly.com/sortly-product-overview) | Dashboard, Items & Folders, Search, Tags |
| [Photos feature](https://www.sortly.com/features/photos/) | Visual inventory intent |
| [View item locations](https://help.sortly.com/view-item-locations-in-sortly) | Multi-location / path clarity |
| Local study captures | `/tmp/sortly-study/` on the research machine (screenshots + text extracts) |

**Limits:** No authenticated Sortly app session; no official Sortly design tokens; marketing pages are not the app. Treat hex/layout notes as **signals**, not specs. Price-centric Sortly fields are out of scope for Backroom’s stockroom job.

---

## 11. One-line brief

**Backroom should feel as easy to *see and walk* as Sortly, while staying a stockroom tool: spaces first, stock actions second, attention always visible — in Backroom’s quiet teal system, not Sortly’s red.**
