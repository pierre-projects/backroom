# Backroom design system

Version 2.0 · Neutral with one accent · November 2026

Backroom is a work tool for small teams who need to find an item, see where it's kept and update it, often on a phone in a stockroom. The interface stays quiet so the records stand out: neutral surfaces, one teal accent for actions and the current place, and plain sentence-case copy.

[Component reference](design-walkthrough.html) · [Printable specimen](../output/pdf/backroom-design-specimen.pdf) · [Frontend codebook](frontend-codebook.md) · [Phase 1 product behavior](phase-1.md)

![Catalog on desktop and item detail on a phone](design-specimen.png)

## Principles

1. **Records first.** Item names, SKUs and locations carry the visual weight. Chrome (navigation, panels, labels) stays gray and light.
2. **One accent, one meaning.** Teal means "you can act here" or "you are here". It is never used for decoration.
3. **Thumb-reachable on phones.** Navigation sits in a bottom tab bar; primary actions are full width under the page title; dialogs open as bottom sheets.
4. **Say what happens.** Buttons name the action (**Add placement**, **Archive**), messages say what changed and what to do next. No slogans, no marketing voice inside the app.
5. **Accessible by default.** WCAG 2.2 AA contrast, visible focus, 44 px touch targets, labels on every control, color always paired with words.

## Color

All values live as CSS custom properties in [`styles.css`](../frontend/src/styles.css). Use the token, never the hex.

| Token | Value | Use |
| --- | --- | --- |
| `--bg` | `#F6F6F4` | Page canvas behind panels |
| `--surface` | `#FFFFFF` | Panels, tables, sidebar, dialogs, inputs |
| `--surface-sunken` | `#EFEFEC` | Neutral badges, hover on ghost controls, read-only fields |
| `--text` | `#1C1D1F` | Headings, record names, body |
| `--text-muted` | `#5E6166` | Descriptions, SKUs and units on phones, table headers, metadata |
| `--text-subtle` | `#8A8C90` | Placeholders, disabled text, chevrons only — never for information |
| `--border` | `#E3E3DF` | Panel edges and row dividers |
| `--border-strong` | `#84868B` | Input and secondary-button edges |
| `--accent` | `#0E6B73` | Primary buttons, links, active navigation, focus ring |
| `--accent-hover` / `--accent-pressed` | `#0B5A61` / `#094A50` | Primary button states |
| `--accent-soft` | `#E6F1F2` | Active sidebar item, business avatar |
| `--brand-mark` | `#E7AF48` | Logo mark only |
| `--success` / `--success-soft` | `#1F6B3A` / `#E7F3EB` | Confirmations, Active badges |
| `--warning` / `--warning-soft` | `#8A4B0F` / `#FCF0E0` | Archived record status, pending invitations |
| `--danger` / `--danger-soft` | `#B42318` / `#FDECEA` | Errors, destructive buttons and row actions |

**Measured contrast** (sRGB relative luminance): text on surface 16.9∶1, muted on canvas 5.8∶1, white on accent 6.2∶1, accent on accent-soft 5.4∶1, control edge on canvas 3.4∶1, each feedback color on its tint ≥ 5.7∶1, white on danger 6.6∶1.

Rule of thumb: about 90% of any screen is `--bg`, `--surface` and text grays. The accent appears on at most one filled button per view.

## Type

System UI fonts (San Francisco, Segoe UI, Roboto) load instantly and look native on staff devices. Monospace for SKUs and codes. `tabular-nums` for dates, counts and any future quantities.

| Role | Size / line height | Weight |
| --- | --- | --- |
| Page title | 24 / 32 px desktop, 20 / 28 px phone | 600 |
| Dialog title | 20 / 28 px | 600 |
| Section title | 16 / 24 px | 600 |
| Body, table rows, buttons, labels | 14 / 20 px | 400; labels, buttons and record names 600 |
| Form inputs | 16 / 24 px on touch and below 1024 px (stops iOS zoom), 14 / 20 px on desktop | 400 |
| Supporting text, hints, table headers | 13 / 20 px | 400; headers 600 |
| Badges, tab labels | 12 / 16 px | 600 / 500 |

Nothing in the product is smaller than 12 px. No uppercase tracked "eyebrow" labels.

## Space, shape and depth

- **Spacing:** 4 px scale — 4, 8, 12, 16, 20, 24, 32, 48. Page gutters 16 px phone, 24 px tablet, 32 px desktop.
- **Radius:** 6 px controls, 8 px panels and dialogs, 12 px top corners on phone sheets, fully rounded badges.
- **Depth:** panels use a 1 px border and no shadow. Only overlays (dialogs, menus) get `--shadow-overlay`.
- **Targets:** controls are 36 px tall with a mouse, 44 px on touch screens and anything narrower than 1024 px. Table rows are at least 52 px, phone rows 56 px.
- **Motion:** 120 ms color transitions on controls, 160 ms fade/slide for dialogs and sheets. Everything is off under `prefers-reduced-motion`.

## Icons

Inline SVG set in [`icons.tsx`](../frontend/src/icons.tsx): 20 px box, 1.75 px round stroke, `currentColor`. Icons are decorative and always sit beside a visible word, except the sheet close button and the sign-out button in the sidebar, which carry an `aria-label`. Don't use emoji or Unicode symbols as icons.

## Layout and navigation

| Width | Navigation | Content |
| --- | --- | --- |
| 1024 px and wider | 240 px white sidebar: logo, business card (name, role, **Switch business**), destinations with icon and label, account block with **Sign out** at the bottom. Active item: teal tint, teal text, 3 px leading bar. | Max 1120 px, 32 px gutters. Title row (title, description, actions on the right), then toolbar, then records. |
| Under 1024 px | 56 px sticky top bar: logo, business name (opens the business list), account button (name, email, **Switch business**, **Sign out**). 64 px fixed **bottom tab bar** with icon over label. | 16 px gutters on phones, 24 px on tablets. Bottom padding clears the tab bar and the home indicator. |

Tabs are filtered by role, so the bar never needs a "More" item:

| Role | Destinations |
| --- | --- |
| Employee | Home, Inventory, Scan, Requests, Locations |
| Manager | Home, Inventory, Scan, Requests, Locations |
| Owner | Home, Inventory, Scan, Requests, Locations |

Every role gets the same five tabs, with the most frequent first. Less frequent pages live in the account menu on phones and in an **Administration** group under the main navigation on desktop, filtered by role:

- **Activity** and **Export data**: Owners and Managers.
- **People** and **Business settings**: Owners only.

On desktop a compact search field sits above the main navigation; on phones the search field is at the top of Home. The Requests tab carries a count badge: requests waiting for review (managers) or approved requests waiting for delivery (employees).

Detail pages show a back link above the title. It returns to where the person came from (**‹ Back to House espresso**, **‹ Back to search results**, **‹ Back to scan result**), restoring that list's filters, page, scroll position and the row they opened. Opened directly, it falls back to **‹ Inventory**, **‹ Requests** or **‹ All locations**. A location's parent is a separate link beside its type badge ("In Stockroom"), so physical hierarchy and the way back never compete. Inventory keeps its search, filters and page in the address. A "Skip to content" link is the first focusable element.

## Components

| Component | Variants and rules |
| --- | --- |
| **Button** | `primary` (teal, one per view), `secondary` (white, strong border), `ghost` (no border, for low-priority header actions), `danger` (only to confirm a destructive action). Label is a verb + noun. A leading plus icon replaces "+ " in text. Pending state renames the label (**Saving…**, **Adding…**) and disables it. |
| **Text button** | Inline row actions (**Remove**, **Revoke**, **Remove access**). Danger tone for removals. |
| **Field** | Label above control, optional hint below, inline error below that with `aria-invalid`. Labels are nouns ("Business name"), hints are one sentence. |
| **Search** | Search icon inside the field, **Search** button beside it, **Include archived** checkbox at the end of the toolbar. Full width on phones. |
| **Data table** | White panel, header row in muted 13 px, 52 px rows, whole row clickable through the name link, chevron at the end. Under 640 px each row restacks: name (+ Archived badge) on line one, `SKU · Unit` muted on line two. Absent SKU reads "No SKU". |
| **Record list** | Simple divided rows inside a panel for short lists (expected locations). Shows the parent name before the location: "Stockroom › **Shelf A**". |
| **Badge** | Neutral (roles, types, Archived in lists), success (Active member), warning (Archived on a detail page, pending invitation), danger. Ordinary active items get no badge. |
| **Notice** | Inline error (`role="alert"`, danger tint, **Try again** when a read failed) and success (`role="status"`, success tint). Sits at the top of the panel it belongs to. |
| **Empty state** | Icon tile, plain title, one sentence on what to do next, optional action that isn't already in the page header. |
| **Dialog** | Title states the question for confirmations (**Archive Espresso beans?**). Centered 512 px card on desktop; bottom sheet on phones with a sticky action row. Background is inert, Escape closes, focus returns to the trigger. Destructive dialogs focus **Cancel** first and confirm with a danger **Archive** / **Remove**. |
| **Loading** | Skeleton rows inside the panel for lists; short text elsewhere. Empty results only show after a successful load. |
| **Pager** | "Page 2 of 5" on the left, **Previous** / **Next** on the right, inside the panel footer. |

## Stock patterns

| Pattern | Rules |
| --- | --- |
| **Quantity** | Always show the unit beside the number (`12.5 kg`, `24 L`; Each shows the number alone). `tabular-nums`, right-aligned in tables. Changes carry a sign (`+6 kg`, `−0.75 kg`); additions use the success color, removals stay in body text. |
| **Quantity field** | Label includes the unit: "Quantity (kg)". Numeric keypad for Each, decimal keypad otherwise. Hint states what's available when removing or moving stock. |
| **Stock by location** | Table of `Parent › Location` with the on-hand quantity. Each row offers its everyday action as a button, **Record usage** when there is stock or **Receive stock** when there is none, and a **More actions** text button. Both carry the location in their accessible names. A location's **Expected items** rows work the same way, named by item; on shelves and bins that table comes before child storage. On phones the row restacks to name, then "15 kg on hand", with the actions wrapping below. |
| **Location action sheet** | **More actions** opens a sheet titled with the location path. It lists only the actions the person's role allows: Record usage, Record waste, Receive stock here, Request more here, plus Transfer from here, Count, Set starting quantity and Set minimum for managers. A one-line hint sits under less obvious actions. |
| **Choosing the shelf** | Usage and waste never guess. Opened from a row, a scan with a shelf, or an item stocked on only one shelf, the sheet shows "From Stockroom › Shelf A · 12 kg available" and focuses Quantity. When several shelves hold the item and none is known, **From** starts on "Choose a shelf" and must be set. |
| **Item header** | **Record usage** (primary) when the item has stock, then **Receive stock**, **Request more** and, for managers, **Transfer**. **Edit item** and **Archive item** live in a **Manage item** section at the end of the page, away from daily actions. |
| **Stock sheet** | One sheet per operation, titled with the action ("Record usage"). The submit button repeats the title. The success message names the amount and place, and what is left there after a removal: "Used 2 kg from Stockroom › Shelf A. 10 kg on hand there." Server errors appear inline in the sheet, and the form keeps its values for a retry. |
| **History** | Newest first. Each row has an icon tile and a past-tense title (Received, Used, Wasted, Moved out/in, Count adjustment, Starting quantity, Reversal), then `place · person`, any note or reference, the signed change, the balance after, and the time. Reversed operations carry a **Reversed** badge. Managers see **Reverse** under reversible rows; it appears once per transfer. |

## Scanning and label patterns

| Pattern | Rules |
| --- | --- |
| **Scan page** | Camera panel and result column side by side on desktop, stacked on phones. The camera never starts on its own; **Start camera** asks for permission. A white rounded aim frame over a dimmed preview. Manual entry ("Type a code or barcode") is always visible. |
| **Result card** | Left border in the accent color for a found item or location, warning color for anything that needs attention. Kicker line names the type and the code in monospace. Primary action **Open item** / **Open location**, plus **Record usage** and **Receive** for items. **Scan again** sits under the card at full width. |
| **Camera problems** | One error notice per cause (denied, no camera, needs HTTPS, failed to start), each saying what to do next. Never a dead end: manual entry stays usable. |
| **Labels and barcodes panel** | On item and location pages. One row per active code (QR label, product barcodes) with the code in monospace. Managers see **Print label** / **Create label**, **Withdraw** / **Remove** (danger, confirmed) and an **Add a product barcode** field. Withdrawn codes are listed once, muted. |
| **Print labels** | Label previews at true aspect ratio on white (a label is paper, even in dark mode). Paper choice as large radio cards, start position, and three numbered print steps including the calibration page. A warning notice while labels would point to a local address. |
| **Selection mode** | **Print labels** on the Inventory and Locations lists switches rows to checkboxes, with a tinted bar showing the count and **Print N labels**. |

## Request patterns

| Pattern | Rules |
| --- | --- |
| **Filter chips** | A horizontal row of pill buttons above the list (`aria-pressed`). Managers start on **Needs review**, employees on **To receive**. The active chip is filled with the accent color. On phones the row scrolls sideways inside itself. |
| **Request row** | `R-12` in muted monospace, then the item name. Second line: destination path and progress ("4 of 6 L received"). Third line: requester and relative time. Status badge on the right: Submitted (warning), Approved and Partly received (accent), Received (success), Rejected, Cancelled and Closed short (neutral). |
| **Request detail** | Title `R-12 · Item`. Quantities strip: Requested / Approved / Received / Still expected. A details panel with links to the item and location, then a timeline: requested, decided, each delivery (reversed ones struck through with a note), closed. Primary action is **Record delivery** while stock is expected; managers also see **Approve**/**Reject** or **Close short**. |
| **Decision sheets** | The title asks the question ("Approve R-1?", "Close R-2 short?"). Approve pre-fills the requested quantity and says it can't be changed later. Reject and Close short need a reason and use the danger button. Delivery pre-fills what is still expected. |
| **Duplicate warning** | The new-request sheet lists open requests for the same item and location in a warning notice, and the submit button changes to **Submit anyway**. |

## Home and low-stock patterns

| Pattern | Rules |
| --- | --- |
| **Home** | Opening a business lands here. Owners and Managers: **Needs review** and **Low stock** count tiles first, then search with **Scan**, then **Requests** beside **Low stock** on desktop (one column on phones), **Request stock**, setup and **Recent activity**. Employees: search with **Scan** (primary) first, a **To receive** tile, then **My requests** and **Low stock**. Cards are table panels: a header with a title, a one-line description and a text link (**See all low items**, **Open requests**, **All activity**). |
| **Setup checklist** | Numbered steps, each with a round mark: empty, accent ring for the next step, or filled success with a check when done. Done steps turn muted. Only the next step has a primary button; the rest are secondary. A new business sees it expanded at the top. Once there is stock or a request to review it collapses to "Continue setup · 2 remaining" below the work, with **Expand**/**Collapse**. **Download a label** is ticked only after a label PDF downloads. |
| **Count tiles** | A row of boxed tiles, each a large tabular number beside its label ("3 Needs review"), linking to the list that shows exactly those records. **Low stock** counts items, like the Inventory "Low stock only" list it opens. A count that failed to load shows "–" with the error above, never 0. |
| **Minimum note** | Under the location name on a stock row: a **Low** warning badge (only when low), then "Min 5 L" in muted small text. The slot sheet subject adds "· min 5 L". |
| **Low row** | Item name (link), location path, then "**3 L** of 5 L · short 2 L" with the current quantity in warning color. On the right: **Request** (secondary, prefilled with the shortfall) or "R-12 open" linking to the request that already covers it. |
| **Search results** | A **Code match** first, as an accent-tinted card with the target's icon. Then **Items** and **Locations** panels, each saying how many matched, or "First 8 matches" when capped. |
| **Export cards** | Two per row on desktop, one on phones. Each card has a title, one sentence saying what the file contains, From/To date fields for history files, and a secondary **Download CSV** button. A note under the cards says times are UTC and states the 50,000-row limit. |
| **Reset link dialog** | Mirrors the invitation dialog. A confirm step explains what happens. Then a read-only link field with "it won't be shown again" help and **Copy link** (primary). |

## Browse patterns

| Pattern | Rules |
| --- | --- |
| **View toggle** | A labelled group of two `aria-pressed` buttons, **Grid** and **List**, each with an icon. It sits at the end of the filters band. The pressed state uses the accent tint. Phones start in Grid and wider screens in List; the person's choice is remembered per browser and surface. Filters, search, paging and selection never reset on a switch. |
| **Visual tile** | The photo when there is one, filling the tile with `object-fit: cover`. Otherwise a `--surface-sunken` tile with up to two initials and a small kind icon in the corner (unit for items: box, weight, drop; type for locations: room, shelf, cabinet, bin). Never accent-colored, and always decorative, because the name beside it carries meaning. Sizes: `sm` 40 px in list rows, `md` 16:10 in cards, `lg` 96 px in the item hero. |
| **Browse card** | Tile, then the record name as the link, then facts ("12 kg on hand", SKU in mono, or "Shelf · 2 locations · 12 items"), then badges, then any stock actions. The card is not itself a link, so its buttons stay separate targets. Grid columns use `auto-fill, minmax(12rem, 1fr)` on desktop, two on phones, one under 352 px. Selected cards get an accent outline; archived cards dim their tile. |
| **Breadcrumbs** | On location pages, above the title: **All locations › Stockroom › Shelf A**. Earlier steps are links; the last is `aria-current="page"`. Separators are drawn in CSS, not read out. The contextual back link, when present, sits above the trail: "where you came from" above "where this is". |
| **Leaf location** | When a location has nothing stored inside it, one quiet line ("No storage inside Shelf A.") replaces the child search and empty state. **Add item here** becomes its primary action, and **Add child location** turns secondary. |
| **Short add** | Creating asks for name and unit. SKU sits under an **Optional details** disclosure in the accent link color. Editing shows every field. |
| **Item hero** | The `lg` tile or photo leads the title row. Owners and Managers get **Add photo / Change photo** (camera icon) and **Remove photo** under it; on phones these sit beside the tile. Low status appears with the totals. |
| **Spaces strip** | On Home, up to six top-level rooms as compact bordered links (small tile, name, contents) with **All locations** at the right of its heading. It never shows counts that aren't real and is hidden until a location exists. |
| **Sold** | **Record sale** sits in **More actions** after waste, with the hint "Sold to a customer; no prices are kept". Its history title is **Sold** with a tag icon. The confirmation follows the removal pattern. |

## Writing

- Sentence case everywhere. Name things the way a café or shop would: items, locations, shelves, people.
- Page descriptions say what the page holds, in under ten words ("Everything your business stocks, with SKU and unit.").
- Buttons: verb + noun. Confirm buttons repeat the verb from the title (**Archive Espresso beans?** → **Archive**).
- Errors say what went wrong and what to do: "Couldn't add Shelf A. Check your connection and try again."
- Empty states tell the person what to do next, or where the content will come from if they can't add it.

| Moment | Copy |
| --- | --- |
| No items, editor | **No items yet** · "Use Add item to create your first one and choose the unit you count it in." |
| Usage recorded | **Used 2 kg from Stockroom › Shelf A** |
| Count | **Counted 15 kg at Stockroom › Shelf A. Adjusted by −0.25 kg.** (or "It matched.") |
| Not enough stock | "There isn't enough stock at this location for that." |
| Reverse a receipt | **Reverse this receipt of 10 kg?** · "Backroom records the opposite movement, so the history shows both." |
| No items, employee | **No items yet** · "Items your team adds will appear here." + **View locations** |
| No search results | **No items match "espresso"** · "Check the spelling, or search by SKU instead." + **Clear search** |
| Placement added | **Stockroom › Shelf A added** |
| Archive item | **Archive Espresso beans?** · "Its expected locations are removed from active use. You can still find it with Include archived, and its history stays available." |
| Remove member | **Remove Sam Lee?** · "They will lose access to Juniper Café right away. Their past activity stays in the history." |

## Research behind the decisions

**Primary UI/UX inspiration:** Sortly — see [Sortly → Backroom design system & UX rules](sortly-inspiration.md) (photo-first browse, space navigation, grid/list, short add flows; adapted for physical spaces, stock actions, and attention).

| Product | Observed workflow | Decision for Backroom |
| --- | --- | --- |
| Sortly | Search can show a folder path, and one item can appear in several folders. | Show the parent name with each expected location so two "Shelf A" entries are distinguishable, in the list and in the picker. [Sortly item locations](https://help.sortly.com/view-item-locations-in-sortly) |
| Square for Retail | Archived items are hidden but found again through a status filter. | Keep **Include archived** in the catalog toolbar and label archived rows. [Square item archival](https://squareup.com/help/us/en/article/8060-archive-items-with-square-for-retail) |
| MarketMan | Teams count stock by storage area on mobile. | Give Locations equal weight with Catalog in the tab bar. For future counts, keep shelf and unit visible while entering a quantity. [MarketMan inventory workflows](https://www.marketman.com/page/food-service-management-software) |

## Future stock work

When Inventory, Scan and Requests ship ([PRD](../backroom_prd_v1.md)), Owners would have more than five destinations. At that point move **Settings** into the account menu and keep the tab bar at five. Quantities use `tabular-nums` and always show their unit beside the number; operations are named (**Receive stock**, **Count shelf**), and a scan result names both item and location before offering an action.
