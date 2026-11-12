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
| Employee | Catalog, Locations |
| Manager | Catalog, Locations, Activity |
| Owner | Catalog, Locations, Activity, People, Settings |

Detail pages show a back link above the title (**‹ Catalog**, **‹ All locations**, **‹ Parent location**). A "Skip to content" link is the first focusable element.

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

## Writing

- Sentence case everywhere. Name things the way a café or shop would: items, locations, shelves, people.
- Page descriptions say what the page holds, in under ten words ("Everything your business stocks, with SKU and unit.").
- Buttons: verb + noun. Confirm buttons repeat the verb from the title (**Archive Espresso beans?** → **Archive**).
- Errors say what went wrong and what to do: "Couldn't add Shelf A. Check your connection and try again."
- Empty states tell the person what to do next, or where the content will come from if they can't add it.

| Moment | Copy |
| --- | --- |
| No items, editor | **No items yet** · "Use Add item to create your first one and choose the unit you count it in." |
| No items, employee | **No items yet** · "Items your team adds will appear here." + **View locations** |
| No search results | **No items match "espresso"** · "Check the spelling, or search by SKU instead." + **Clear search** |
| Placement added | **Stockroom › Shelf A added** |
| Archive item | **Archive Espresso beans?** · "Its expected locations are removed from active use. You can still find it with Include archived, and its history stays available." |
| Remove member | **Remove Sam Lee?** · "They will lose access to Juniper Café right away. Their past activity stays in the history." |

## Research behind the decisions

| Product | Observed workflow | Decision for Backroom |
| --- | --- | --- |
| Sortly | Search can show a folder path, and one item can appear in several folders. | Show the parent name with each expected location so two "Shelf A" entries are distinguishable, in the list and in the picker. [Sortly item locations](https://help.sortly.com/view-item-locations-in-sortly) |
| Square for Retail | Archived items are hidden but found again through a status filter. | Keep **Include archived** in the catalog toolbar and label archived rows. [Square item archival](https://squareup.com/help/us/en/article/8060-archive-items-with-square-for-retail) |
| MarketMan | Teams count stock by storage area on mobile. | Give Locations equal weight with Catalog in the tab bar. For future counts, keep shelf and unit visible while entering a quantity. [MarketMan inventory workflows](https://www.marketman.com/page/food-service-management-software) |

## Future stock work

When Inventory, Scan and Requests ship ([PRD](../backroom_prd_v1.md)), Owners would have more than five destinations. At that point move **Settings** into the account menu and keep the tab bar at five. Quantities use `tabular-nums` and always show their unit beside the number; operations are named (**Receive stock**, **Count shelf**), and a scan result names both item and location before offering an action.
