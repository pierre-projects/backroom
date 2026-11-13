# Backroom frontend codebook

Version 3.1 · Coral · [Design system](design-system.md) · [Component reference](design-walkthrough.html)

How the design system is implemented in the React, TypeScript and plain-CSS frontend, and the rules for extending it. The [Phase 1 contract](phase-1.md) governs data and permissions.

## Where things live

| File | Responsibility |
| --- | --- |
| [styles.css](../frontend/src/styles.css) | **Tokens** (`:root`), base element styles, and every shared component: buttons, fields, badges, panels, data table, record list, notices, empty state, pager, dialog/sheet, menu. |
| [workspace.css](../frontend/src/workspace.css) | **Layout only**: brand, public pages (landing, auth, chooser), the workspace shell (sidebar, top bar, bottom tab bar), page header, toolbar, detail grid, activity list. Reads tokens, defines none. |
| [icons.tsx](../frontend/src/icons.tsx) | `Icon` component and the outline icon set. Add new glyphs here in the same 20 px / 1.75 stroke style. |
| [components.tsx](../frontend/src/components.tsx) | `Brand`, `BrandMark`, `PageHeader`, `Field`, `Dialog`, `Badge`, `Empty`, `Loading`, `ErrorMessage`, `SuccessMessage`, `Pager`, plus the `useResource` / `useAll` data hooks. |
| [stock.tsx](../frontend/src/stock.tsx) | Stock UI: `formatQuantity`, `locationPath`, `useStockLocations` (active leaves with parent labels), `StockDialog` (one sheet for receive/usage/waste/transfer/count/opening, with a per-sheet operation key reused on retry), `SlotSheet` (role-filtered actions for one location), `MovementList`, `ReverseDialog`. |
| [browse.tsx](../frontend/src/browse.tsx) | Browse and explorer building blocks: `useBrowseView` (Grid/List per surface, remembered per browser), `ViewToggle`, `VisualTile`, `FolderTile`, `BrowseCard`, `Breadcrumbs`, `StatsStrip` (summary strip), `SearchScanLink`, `useShowFolders` / `FolderSwitch`, `newBadge`, `spaceFacts`, and `resizePhoto` for uploads. |
| [navigation.tsx](../frontend/src/navigation.tsx) | Contextual return: `RecordLink` stashes the origin (URL, label, scroll, focused row), and back links restore it. Hierarchy links stay separate. |
| [workspace.tsx](../frontend/src/workspace.tsx) | Shell (coral rail, white header search, phone top bar and tab bar), the `destinations` array (one list drives both sidebar and tab bar, filtered by role), `AccountMenu`, Settings page. |
| [api.ts](../frontend/src/api.ts), [types.ts](../frontend/src/types.ts) | Fetch wrapper (CSRF, JSON or raw `Blob` bodies with their own content type) and the API types. |
| [home.tsx](../frontend/src/home.tsx), [catalog.tsx](../frontend/src/catalog.tsx), [locations.tsx](../frontend/src/locations.tsx), [scan.tsx](../frontend/src/scan.tsx), [codes.tsx](../frontend/src/codes.tsx), [labels.tsx](../frontend/src/labels.tsx), [requests.tsx](../frontend/src/requests.tsx), [search.tsx](../frontend/src/search.tsx), [imports.tsx](../frontend/src/imports.tsx), [exports.tsx](../frontend/src/exports.tsx), [people.tsx](../frontend/src/people.tsx), [auth.tsx](../frontend/src/auth.tsx), [App.tsx](../frontend/src/App.tsx) | Screens. Own their copy, pending states and navigation after success. |

Rules:

- Never write a hex value, pixel font size or ad-hoc shadow in a component. Use a token; add one in `:root` if it's genuinely new.
- No global element rules that style `button` or `a` beyond the base reset — style through classes (`.button.primary`, `.text-button`, `.nav-link`).
- Breakpoints: `39.99rem` (phone, under 640 px) and `63.99rem` (shell switch, under 1024 px). Use `max-width` media queries with those values only.

## Tokens

```css
:root {
  --bg: #f4f5f7;  --surface: #ffffff;  --surface-sunken: #eef0f3;  --surface-hover: #f7f8fa;
  --text: #111827;  --text-muted: #636975;  --text-subtle: #9ca3af;
  --border: #e5e7eb;  --border-strong: #80868f;
  --accent: #c2331f;  --accent-hover: #a82b1a;  --accent-pressed: #8e2415;  --accent-soft: #fdece8;
  --on-accent: #ffffff;  --focus: #c2331f;
  --sidebar-bg: #d63f2a;  --sidebar-surface-active: rgb(0 0 0 / .18);  --sidebar-surface-hover: rgb(0 0 0 / .1);
  --sidebar-border: rgb(255 255 255 / .16);  --sidebar-text: #ffffff;  --sidebar-text-muted: rgb(255 255 255 / .9);
  --brand-mark: #ffffff;  --brand-ink: #c2331f;
  --success: #1f6b3a;  --success-soft: #e7f3eb;
  --warning: #8a4b0f;  --warning-soft: #fcf0e0;
  --danger: #8f1d3f;  --danger-hover: #75162f;  --danger-soft: #fbe9ef;

  --text-xs: .75rem;  --text-sm: .8125rem;  --text-md: .875rem;  --text-lg: 1rem;  --text-xl: 1.25rem;  --text-2xl: 1.5rem;
  --space-1: .25rem;  --space-2: .5rem;  --space-3: .75rem;  --space-4: 1rem;  --space-5: 1.25rem;  --space-6: 1.5rem;  --space-8: 2rem;  --space-12: 3rem;
  --radius-control: 8px;  --radius-panel: 10px;  --radius-sheet: 14px;  --radius-pill: 999px;
  --shadow-card: 0 1px 2px rgb(17 24 39 / .05);  --shadow-overlay: 0 12px 32px rgb(17 24 39 / .16), 0 2px 6px rgb(17 24 39 / .08);
  --control-height: 2.25rem;   /* 2.75rem on touch and under 1024px */
  --duration-control: 120ms;  --duration-overlay: 160ms;
  --sidebar-width: 14.5rem;  --topbar-height: 3.5rem;  --tabbar-height: 4rem;
}
```

This block mirrors [`styles.css`](../frontend/src/styles.css); when they disagree, the stylesheet wins and this copy needs updating.

## Component API

```tsx
<PageHeader
  title="Shelf A"
  description="Optional one line"
  back={{ to: origin.url, label: 'House espresso' }}             {/* where you came from */}
  trail={<Breadcrumbs trail={[{ label: 'All locations', to: root + '/locations' }, { label: 'Stockroom', to: … }, { label: 'Shelf A' }]} />}  {/* where this is */}
  media={<VisualTile name="Shelf A" icon="shelf" size="lg" />}>
  <button className="button secondary">Edit item</button>
</PageHeader>

<button className="button primary"><Icon name="plus" />Add item</button>
<button className="button danger">Archive</button>

<Field label="SKU (optional)" error={skuError}>
  <input name="sku" />
  <small>Saved in uppercase.</small>   {/* becomes the hint, wired with aria-describedby */}
</Field>

<Badge tone="warning">Archived</Badge>        {/* neutral | success | warning | danger | accent */}

<Empty icon="search" title="No items match “oat”" action={<button className="button secondary">Clear search</button>}>
  Check the spelling, or search by SKU instead.
</Empty>

<Dialog title="Archive Espresso beans?" close={close}>
  <p>…</p>
  <div className="form-actions">
    <button className="button secondary" data-autofocus onClick={close}>Cancel</button>
    <button className="button danger">Archive</button>
  </div>
</Dialog>
```

`Dialog` renders into `document.body`, sets `inert` on `#root` while open, traps Tab, closes on Escape or backdrop click, and returns focus to the trigger. Initial focus goes to `[data-autofocus]`, else the first editable field, else the first control. On phones it becomes a bottom sheet and `.form-actions` sticks to the bottom.

### Data table pattern

```tsx
<div className="panel table-panel">
  <table className="data-table">
    <thead><tr><th scope="col">Item</th><th scope="col">SKU</th><th scope="col">Base unit</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
    <tbody>
      <tr className="linked">
        <td>
          <span className="name-line"><Link className="record-link" to={…}>{name}</Link>{archived && <Badge>Archived</Badge>}</span>
          <span className="cell-secondary mobile-only">…phone-only second line…</span>
        </td>
        <td className="desktop-only mono">{sku}</td>
        <td className="desktop-only">{unit}</td>
        <td className="cell-chevron"><Icon name="chevronRight" /></td>
      </tr>
    </tbody>
  </table>
  <Pager data={page} onPage={setPage} />
</div>
```

- `tr.linked` stretches the record link over the whole row and draws the focus ring on the row.
- `.desktop-only` cells hide under 640 px; `.mobile-only` content shows only there. Repeat the essentials (SKU, unit, type) in the mobile line.
- Row actions go in `td.cell-actions` (right aligned; second column on phones). Controls that must stay visible on phones (role select, status) use `td.cell-inline`.

## States

- **Reading:** `useResource` gives `data`, `error`, `reload`. Show `<Loading variant="list" />` inside the panel while loading, `<ErrorMessage retry={reload} />` on failure, and the empty state only after a successful empty read.
- **Saving:** disable the submit, rename it (**Saving…**, **Adding…**, **Archiving…**), keep the form values on failure, and confirm success with `SuccessMessage` (polite status) or by navigating to the result.
- **Access:** all roles see Home, Inventory, Scan, Requests and Locations and record everyday stock changes; Owners and Managers edit records, import, review requests and see Activity and Export data; Owners see People and Business settings. Hide controls the role can't use, and still handle a server 403 gracefully.
- **Missing values:** "No SKU" on phones, an em dash with screen-reader text "No SKU" in desktop tables, "Not set" on detail pages.

## Verification checklist

1. `npm --prefix frontend run build` (typecheck + build).
2. `./scripts/e2e.ps1` — the Playwright suite (seven spec files in `frontend/e2e`) covers onboarding, placements, invitations on a 390 px phone, role restrictions, archival and session expiry, stock movements, labels and scanning, restocking, Home and resets, browse (grid/list, breadcrumbs, photos, sales) and CSV import, and assert no horizontal scroll on the phone.
3. Look at every screen at 375, 768 and 1280 px: tab bar visible and role-filtered, nothing hidden behind it, long names and SKUs wrap, dialogs become sheets.
4. Keyboard only: skip link, sidebar/tab order, account menu (Escape returns focus), dialog trap, visible focus ring on rows and controls.
5. Re-run the token contrast check when changing any color (pairs and minimums are listed in [design-system.md](design-system.md#color)).
