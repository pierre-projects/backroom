# Phase 7 CSV import

One spreadsheet takes a business from nothing to placed, counted stock. **Inventory → Import items** (Owners and Managers) reads a CSV, shows exactly what will happen, and imports only after you confirm.

## What is available

- **Template.** **Download template** gives the columns and three example rows. Excel, Numbers and Google Sheets files saved as CSV work, including European files that use `;` and decimal commas (`2,5`). A Backroom **Stock by location** export imports as it is.
- **Columns:** `Item, SKU, Unit, Location, Starting quantity, Minimum`. Only Item and Unit are required.
  - **Unit:** `each`, `g`, `kg`, `mL`, `L`, or the full names.
  - **Location:** a path such as `Stockroom > Coffee shelf` (`›` also works).
  - **Starting quantity:** follows the unit's rules; `0` or blank places the item without stock.
  - **Minimum:** needs a Location.
  - **Header names:** case-insensitive. `Name` and `On hand` are accepted as aliases, and extra columns are ignored.
- **One item, several shelves.** Rows with the same SKU, or the same name when there is no SKU, become one item placed on several locations. Their units must agree.
- **Review before saving.** The review shows:
  - counts: new items, already existing, possible duplicates, problems, and new locations;
  - the locations that will be created;
  - every row with its spreadsheet line number, status and reason.

  | Status | Meaning |
  | --- | --- |
  | **New** | Will be imported |
  | **Already exists** | Its SKU is already in this business. The existing item is never changed. |
  | **Possible duplicate** | No SKU, and an item with that name exists. Tick **Include** if it really is a different item. |
  | **Problem** | Won't be imported. The reason says what to fix, for example `Unit "bottles" isn't one of each, g, kg, mL or L.` |

- **Import.** **Import N rows** creates, in order: the new locations, the items, their placements, starting quantities and minimums. The result lists what was created and how many rows were skipped or had problems, with links to Inventory and Locations.
- **Entry points:** the Inventory header (**Import items**), the empty Inventory (**Import a spreadsheet**), and the Home setup step "Add the items you stock".

## Rules

- **New locations:** a missing top level becomes a Room; deeper levels become Shelves.
  - Path matching ignores case and extra spaces, and only considers active locations in this business.
  - An archived location in a path, or two same-named siblings, is a problem rather than a guess.
- **Items go only on locations with nothing stored inside them.** Each of these is a problem:
  - a row whose location has locations inside it;
  - a row whose location would get locations inside it from another row;
  - a row that would create a location inside a place that already holds items.
- **The same item on the same location twice** in one file is a problem on the later row.
- **Starting quantities** are recorded as Starting quantity operations with the note "Imported from catalog.csv". They appear in history like any other operation and can be reversed.
- **One transaction.** If anything unexpected fails, nothing is saved. Rows with problems are reported, never partly applied.
- **Safe retries.** Each import carries a key created when the review loads, and changed whenever the included rows change.
  - Retrying the same request replays the first result (`200`, `replayed: true`).
  - The same key with a different file returns `409 IMPORT_KEY_REUSED`.
  - Imports in one business run one at a time.
- **Re-checked on import.** The file is planned again against current data, so an item created after the review is skipped as a duplicate rather than created twice.
- **Limits:** 1 MB and 2,000 rows (`413 IMPORT_TOO_LARGE`). A file without Item and Unit columns returns `400 IMPORT_HEADER`.
- **Audit:** each import records `ITEM_IMPORT_COMPLETED` (file, rows, created, skipped, problems). Every location, item, placement, starting quantity and minimum it creates is audited as if entered by hand.
- **Round trip:** the stock export's location column shows a parent and a location (`Dry goods › Cups bin`). A deeper location therefore comes back two levels deep in another business.

## API

All routes are under `/api/v1/organizations/{orgId}/imports/items`, for Owners and Managers.

| Route | Purpose |
| --- | --- |
| `POST /preview` | Raw `text/csv` body (≤ 1 MB) → `{ summary, rows[] }`; each row has `line`, `status`, `messages`, and normalized fields |
| `POST /` | `{ importKey, fileName, csv, include: [lines] }` → `201` with counts, or `200` on a replay |
| `GET /template.csv` | The template |

## Design

- **The `imports` package** reads with `ItemQueries`, `LocationQueries.tree` and `LocationContentsCounter`. It writes only through `LocationService.create`, `CatalogService.create`/`place`/`setMinimum` and `StockService.openingBalance`, so every validation, lock and audit rule of manual entry applies.
- **Planning** is pure (`ImportPlanner`), and the same planner runs for preview and apply.
- **Reading:** `CsvReader` is the counterpart to the exports `CsvWriter`.
- **`V11`** adds `import_batches`, one row per applied import, keyed by `(organization_id, import_key)`.
- **Ledger keys:** starting-quantity operation keys are derived from the import key and the line number, so the ledger is idempotent too.

## Verification

- **`CsvReaderTests`:** quotes, `""`, embedded newlines and their line numbers, BOM, `;` detection, ragged rows.
- **`imports.ImportIntegrationTests`:**
  - **Preview:** every status and its message; locations to create.
  - **Apply:** items, nested locations (types and parents), balances, minimums, the import note, a ledger that matches its balances, and audit.
  - **Duplicates and retries:** an existing SKU is untouched; a possible duplicate is included on request; a replay; key reuse; a fresh preview after import.
  - **Limits:** header, empty file, the 2,000-row and 1 MB limits; Employees refused.
  - **Isolation and round trip:** isolation between businesses; a stock export from one business imported into another.
- **`frontend/e2e/import.spec.ts`:** at 390 px, an Owner:
  1. downloads the template;
  2. uploads a file with new, existing, possible-duplicate and problem rows, and checks the counts and reasons;
  3. includes the possible duplicate and imports;
  4. checks the result;
  5. finds the items in Inventory and walks to the new **Cups bin** by its breadcrumbs.
- **Screenshots:** choose, review and result at 1280, 390 and 320 px. No horizontal overflow and no page errors.
