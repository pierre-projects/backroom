# Phase 6 browse, photos and sales

Builds on Phases 1–5 and the direct-stock-actions follow-up (`9d99249`). The audited behaviour stays as it was: role-based Home, row stock actions, shelf-choice rules, contextual back, and Download a label. This phase adds the Sortly-style *see and walk* layer ([inspiration](sortly-inspiration.md)), a Sold reason and item photos.

## What is available

- **Grid and list.** Inventory, the Locations index and a location's contents each have a **Grid / List** toggle.
  - **Defaults:** phones (under 640 px) start in Grid, wider screens in List.
  - **Memory:** the choice is remembered per browser for each surface: `backroom.view.items`, `backroom.view.locations`.
  - **What carries across:** search, filters, the page and label selection all survive a switch.
- **Visual tiles.** Every item and location shows its photo when it has one. Otherwise it shows a neutral tile with initials and a small icon (unit for items, type for locations).
  - Tiles are decorative (`aria-hidden`); the record name next to them is the link.
  - Grid cards show the tile, name, "12 kg on hand" and Low/Archived badges.
  - On a location, each card also carries the item's **Record usage** / **Receive stock** and **More actions** buttons.
- **Breadcrumbs.** A location page shows "All locations › Stockroom › Shelf A" above its title. The contextual back link still appears above the trail when you arrived from another record. The trail replaces the old "In Stockroom" link.
- **Space cards.** Locations show their type and contents ("2 locations · 12 items", or "Empty").
  - A location with nothing stored inside it shows one quiet line instead of an empty search panel.
  - On such a location, **Add item here** is the primary action.
- **Shorter add.** Creating an item asks only for its name and unit. SKU sits under **Optional details**; editing an item still shows every field.
  - **Add item here** (Owners and Managers, on a location with nothing stored inside it) creates the item, places it on that location, and opens **Receive stock** with that location already chosen.
- **Item hero.** The item page leads with its tile or photo beside the name. Low status appears with the totals.
- **Home Spaces strip.** Up to six top-level rooms appear as compact links, plus **All locations**. For Owners and Managers it sits after the attention cards; for Employees, after their **To receive** tile. It is hidden until a location exists.
- **Record sale.** In **More actions** for every role that can record usage. It uses the same shelf rules and confirmation ("Sold 1 L from Stockroom › Shelf A. 3 L on hand there."). History and exports say **Sold**, and it can be reversed like any removal. No prices, customers or orders are kept.
- **Item photos.** Owners and Managers can **Add photo**, **Change photo** or **Remove photo** under the item's hero tile; phones offer the camera.
  - The browser resizes a photo to at most 1024 px and re-encodes it as JPEG (quality 0.82) before upload.
  - Photos appear on Inventory cards and rows, location contents, and the item hero.

## Rules

- **Photos:**
  - **Format and limits:** a raw `image/jpeg` body of at most 600 KB. Anything else is refused:
    - another type: `415 UNSUPPORTED_IMAGE`;
    - too large: `413 PHOTO_TOO_LARGE`;
    - an unreadable JPEG: `400 INVALID_IMAGE`;
    - more than 2048 px on a side: `400 IMAGE_TOO_BIG`.
  - **Checks:** the server reads the declared dimensions from the JPEG header before decoding, so a small file can't claim an enormous image. It then fully decodes the photo to prove it is real.
  - **One per item.** Replacing a photo changes `photoVersion` (the time it was saved, always increasing), so image URLs (`?v=`) can be cached privately for a year. Archived items keep their photo but can't change it.
  - **Audit:** `ITEM_PHOTO_SET` and `ITEM_PHOTO_REMOVED`.
- **Sales:** `SALE` is a removal like usage and waste.
  - Not enough stock: `409 INSUFFICIENT_STOCK`.
  - Retries with the same operation key apply once.
  - Audited as `STOCK_SOLD`.
  - Unknown reasons are refused with `400`.
- **Counts:** a location's `itemCount` counts active placements of unarchived items directly on it, and `childCount` counts active child locations. A room usually shows locations, not items, because items sit on shelves and bins.

## API

All routes are under `/api/v1/organizations/{orgId}`.

| Route | Change |
| --- | --- |
| `GET /locations/{id}` | Adds `ancestors[]` (`id`, `name`, `type`), outermost first |
| `GET /locations` | Each row adds `childCount` and `itemCount` |
| `GET /items`, `/items/{id}`, `/locations/{id}/items` | Each item adds `photoVersion` (null without a photo) |
| `PUT /items/{id}/photo` | Raw JPEG body → `{ photoVersion, width, height }`. Owner, Manager |
| `DELETE /items/{id}/photo` | `204`. Owner, Manager |
| `GET /items/{id}/photo` | The JPEG, with `Cache-Control: private, max-age=31536000`, an ETag and `nosniff`. Any member |
| `POST /inventory/removals` | `reason` accepts `SALE` |

## Design

- **Migrations:**
  - `V9` widens the `stock_operations` type check with `SALE`.
  - `V10` adds `item_photos`, keyed by organization and item, with check constraints repeating the size and dimension limits.
- **Breadcrumbs:** one org-filtered recursive query (depth capped at 32) instead of fetching every parent.
- **Counts:** grouped queries per page. Item counts come from inventory through `LocationContentsCounter`, so `locations` never imports `inventory` (the `LocationMutationPolicy` pattern).
- **Photos** live in PostgreSQL rather than object storage, to keep the one-app-one-database topology and the $0–5 host. Lists read only `(item_id, updated_at)`, never the bytes.
- **Frontend:**
  - Shared browse pieces live in `frontend/src/browse.tsx`: `useBrowseView`, `ViewToggle`, `VisualTile`, `BrowseCard`, `Breadcrumbs`, `spaceFacts` and `resizePhoto`.
  - Card links are `RecordLink`s, so contextual back restores focus to the card that was opened.

## Deferred, deliberately

- **Equipment / assets.** Individual objects (a particular fridge or scale) need their own identity, condition, location and custody history, and must never feed stock balances. That is a new `assets` module with its own tables, labels and permissions, sketched in [architecture](architecture.md#deferred-equipment-and-assets). Until it is built, no checkout or assignment UI exists. A brew scale stays an ordinary Each item.
- **Location photos.** Space cards use type tiles. Item photos come first because recognising a product matters more than recognising a shelf. The same storage pattern would extend to locations.
- **Import, tags, custom fields, email alerts, suppliers and prices.** Out of scope for this phase. Import is the next candidate if catalog setup becomes the adoption bottleneck.

## Verification

- **`BrowseIntegrationTests`:**
  - **Trail and counts:** the trail order; archived children, archived items and removed placements excluded from counts; isolation between businesses.
  - **Sales:** history, audit, CSV, insufficient stock, reversal and unknown-reason refusal.
  - **Photos:** type, garbage, size and dimension refusals; roles; versions in the item and list views; private caching headers; isolation; replace; remove; audit.
- **`CsvWriterTests`** and every earlier integration test still pass. The migration count is now ten.
- **`frontend/e2e/browse.spec.ts`:**
  - desktop List and phone Grid defaults; the toggle remembered across a reload, with filters and search kept;
  - focus restored to a grid card after Back;
  - breadcrumbs from All locations to Stockroom to Shelf A;
  - **Add item here** with SKU hidden, receiving onto the shelf;
  - **Record sale** with the confirmation and a "Sold" history row;
  - a 1600 px photo uploaded, resized to 1024 px, and shown in the hero and on its card;
  - no horizontal scroll at 390 px.
- `foundation.spec.ts` opens **Optional details** before typing a SKU.
- Screenshots of Inventory, Locations, a bin and Home at 1280, 390 and 320 px as an Owner: no horizontal overflow and no page errors.
