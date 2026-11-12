# Phase 5 usability

## What is available

- **Home.** Every business opens on Home, which shows:
  - a search field;
  - **Low stock**: the first five locations below their minimum, each with what is missing and a **Request** button, or a link to the request already open for it;
  - **Requests**: counts that link to the filtered list, plus requests waiting for review (Owners and Managers) or your own requests (Employees);
  - **Recent activity** (Owners and Managers);
  - **Set up** checklist (Owners and Managers): add locations, add items, record stock, print labels and scan one, invite your team (Owners). Steps tick themselves from the business's data. Printing is remembered per browser, because the server doesn't record prints. **Hide** dismisses the checklist in that browser.
- **Low-stock minimums.** Each item can have a minimum at each of its locations ("keep at least 5 L on Stockroom › Shelf A").
  - A location is **low** when its quantity is below the minimum. Exactly at the minimum is not low.
  - Owners and Managers set, change or remove a minimum from an item's stock row (**Update** → **Set minimum**).
  - Stock rows show "Min 5 L" and a **Low** badge. The Inventory list shows **Low** on the item.
  - **Request more** from a low row is prefilled with the shortfall.
  - Removing a location from an item, or archiving the item, clears its minimums.
- **Search.** One search box, on Home and at the top of the desktop sidebar:
  - finds items by name or SKU and locations by name, up to 8 of each;
  - a pasted label link, a label code or a registered barcode shows as a **Code match**;
  - archived records are left out.
- **Inventory filters.** Inventory gains **All locations / a location** and **Low stock only** filters. Both are kept in the address, so Home's **See all** link opens the filtered list.
- **Export data** (Owners and Managers): four CSV files.
  - **Stock by location** and **Items** are snapshots of right now.
  - **Stock history** and **Restock requests** cover a date range, the last 90 days by default.
- **Password reset links.** An Owner opens **People** → **Reset password** and gives the one-time link to the person. Opening the link sets a new password and signs in. Every other session on that account then ends. The sign-in page explains this under **Forgot your password?**.

### Navigation

Navigation is now **Home, Inventory, Scan, Requests, Locations** for every role.

The less frequent pages moved off the tab bar. They are in the account menu on phones and in the **Administration** group in the desktop sidebar:

| Page | Who sees it |
| --- | --- |
| Activity | Owners and Managers |
| Export data | Owners and Managers |
| People | Owners |
| Business settings | Owners |

## Rules

- **Minimums:**
  - follow the item's unit (whole numbers for Each, up to three decimals otherwise) and must be greater than zero;
  - can only be set on a location the item is placed at (`409 NOT_PLACED`);
  - are audited as `MINIMUM_SET`.
- **Exports:**
  - Format:
    - UTF-8 with a byte order mark, so Excel reads accents and "›" correctly;
    - RFC 4180 quoting and CRLF line ends;
    - dates and times in ISO 8601 UTC; quantities as plain numbers, with a separate unit column.
  - **Formula guard:** any text cell starting with `=`, `+`, `-`, `@`, a tab or a carriage return is prefixed with `'`, so a spreadsheet shows it rather than running it. Numbers are never prefixed.
  - **Size limit:** an export over 50,000 rows returns `413 EXPORT_TOO_LARGE` before anything downloads; choose a shorter range.
  - Every export is audited as `DATA_EXPORTED`, with the file, the row count and the range.
- **Password resets:**
  - Only Owners can create links, and not for themselves.
  - An Owner can reset someone only when **every** business that person actively belongs to is one the Owner owns. Otherwise the request returns `409 RESET_NOT_ALLOWED`, which stops an Owner from taking over an account another business relies on.
  - Links are 256-bit random tokens. Only their SHA-256 is stored.
  - Each link works once and expires after 24 hours. A newer link cancels older ones, and using a link cancels any others.
  - An expired, used or cancelled link returns `409 RESET_UNAVAILABLE`.
  - New passwords follow the registration rules (12 to 72 bytes).
  - A reset increments the account's `credentials_version`.
  - Audit records `PASSWORD_RESET_ISSUED` and `PASSWORD_RESET_COMPLETED`. Neither the token nor the password appears in audit metadata or logs.
- **Ending old sessions:**
  - Each session remembers the credentials version it signed in with. A filter compares it with the account and answers a stale session with `401 SESSION_ENDED`; the app then returns to sign-in.
  - The account's version is cached for 30 seconds per user. The reset clears that cache before and after it commits, so the old session ends on its next request.
- **Not built yet:**
  - Neither sign-in nor reset is rate-limited. Reset tokens can't be guessed (256 bits); rate limiting belongs with the deployment work.
  - There is no email. A sole Owner who forgets their password can't recover it in the app, which is why the sign-in help suggests adding a second Owner.

## API

All business routes are under `/api/v1/organizations/{orgId}`.

| Route | Purpose |
| --- | --- |
| `PUT /items/{id}/placements/{locationId}/minimum` | `{ minimum }`; `null` removes it. Owner, Manager. `204` |
| `GET /inventory/low-stock?locationId=&page=&size=` | Low slots by item name: item, location path, quantity, minimum, shortfall. All roles |
| `GET /inventory/balances?itemId=` | Now also `minimum` and `low` per location |
| `GET /items?lowStock=true&locationId=` | Item list filters; each item now has `low` |
| `GET /inventory/movements` | Now also works with no `itemId` or `locationId`: the whole business's history, newest first |
| `GET /search?q=` | `{ q, items[], locations[], code }`, with up to 8 items and 8 locations. All roles |
| `GET /exports/{items\|stock\|movements\|requests}.csv?from=&to=` | CSV download. `from`/`to` are ISO dates and apply to movements and requests. Owner, Manager |
| `POST /members/{userId}/password-reset` | `201 { userId, displayName, resetPath, expiresAt }`; the link is shown once. Owner |
| `POST /api/v1/auth/password-reset/preview` | `{ token }` → `{ displayName, email, expiresAt }`. Public, CSRF-protected |
| `POST /api/v1/auth/password-reset` | `{ token, newPassword }` → `{ email }`. Public, CSRF-protected |

## Design

- **Migrations:**
  - `V7` adds `item_placements.minimum_quantity` (nullable, `> 0`).
  - `V8` adds `password_resets` and `users.credentials_version`.
- **Low stock** is computed in queries (`quantity < minimum` on active placements of unarchived items), never stored, so it can't drift from the ledger.
- **Home** has no aggregate endpoint. Its cards call existing endpoints. The open request shown beside a low slot comes from `GET /requests?filter=OPEN`, so inventory still never depends on restocking.
- **`search` module:** reads only through `ItemQueries.search`, `LocationQueries.search` and `ScanService.resolve`. A code match is returned only when the code resolves to an active item or location in the same business.
- **`exports` module:**
  - It is the one read-only reporting module that queries other modules' tables directly with SQL. A 50,000-row file is then one streamed query (fetch size 500, read-only transaction, so the file is a consistent snapshot) instead of thousands of lookups.
  - Every joined table is filtered by `organization_id`.
  - It writes nothing except its audit event.

## Verification

- **`UsabilityIntegrationTests`:**
  - **Low stock:**
    - low calculation, including exactly at the minimum, empty new slots, clearing a minimum and removing a placement;
    - the list and filters;
    - minimum validation, roles and tenant isolation;
    - archived items leaving the list.
  - **Search:** by name and SKU; by pasted QR link, label code and barcode; isolation between businesses.
  - **Exports:**
    - CSV content, BOM, quoting and the formula guard;
    - date ranges, a backwards range (`400`) and an unknown file (`404`);
    - roles, isolation and audit;
    - the 50,000-row limit (`413`, nothing audited).
  - **Password reset:**
    - a single-use link and weak-password refusal;
    - the old session ended with `401 SESSION_ENDED`, the old password refused and the new one accepted;
    - only the hash stored, and no token or password in audit;
    - Owner-only issuing, the cross-business refusal, Owner-of-both allowed, newer links replacing older ones, and expiry.
- **`CsvWriterTests`:** quoting, the formula guard, and plain numbers and dates.
- **`frontend/e2e/home.spec.ts`:**
  - the checklist counts setup progress;
  - a minimum set from the item page makes Home show low stock;
  - **Request** creates a request prefilled with the shortfall, and Home then links to it;
  - **See all** opens the filtered Inventory;
  - search finds the item by SKU;
  - Export data downloads a stock CSV with the right rows;
  - an Owner issues a reset link, the employee sets a new password from it, and the employee's phone session is sent back to sign-in;
  - no horizontal scroll at 390 px.
- `foundation.spec.ts` now expects Home after creating or joining a business.
