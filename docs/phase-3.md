# Phase 3 physical scanning

## What is available

Owners and Managers print QR labels for items and locations and link existing product barcodes (UPC/EAN) to items. Anyone in the business can scan in two ways:

- with the phone's ordinary camera: a printed QR label opens Backroom, through sign-in if needed;
- from the **Scan** tab: in-app camera or typed code.

Either way they land on the item or location, with the stock actions their role allows. Scanning never changes stock by itself: every stock change still goes through a sheet the person submits.

Navigation: **Scan** is a tab for every role. Owners open **Business settings** from the account menu (phone) or the gear beside their name (desktop).

## Codes

| Kind | Value | Rules |
| --- | --- | --- |
| QR label | 10 Crockford base32 characters, shown as `7K4Q-2M9X-QA`. The QR holds `{BACKROOM_PUBLIC_URL}/s/{token}`. | Globally unique, random, never recycled. One active QR per item or location; reprinting reuses it. Withdrawing it is permanent; printing again creates a new code. |
| Product barcode | UPC-A, UPC-E, EAN-13, EAN-8 or GTIN-14, stored as GTIN-14 with leading zeros kept. | Check digit validated. `036000291452` and `0036000291452` are the same product. One active item per barcode per business; to move a barcode, remove it and link it again. Barcodes only identify items. |

- Codes are permanent records. A database trigger rejects reassigning, un-withdrawing or deleting a code.
- Renaming or moving an item or location doesn't change its code.
- Archived resources still resolve, and are shown read-only.
- Codes identify; they never authorize.
- Every lookup checks membership before returning details.
- Codes from businesses the person doesn't belong to look exactly like unknown codes.

## Scan results

`POST /organizations/{orgId}/scan-resolutions` with `{ value, format? }` returns one of:

| Status | Shown as |
| --- | --- |
| `ITEM` / `LOCATION` | Name, detail and **Open**. Items also offer **Record usage** and **Receive**, which open the item with that sheet. Archived resources are read-only. |
| `NOT_REGISTERED` | "Barcode … isn't linked yet". Managers get **Link to an item**. |
| `REVOKED` | "This label was withdrawn". |
| `OTHER_ORGANIZATION` | "This label belongs to …", with **Switch**. Only shown when the person is a member there. |
| `NOT_FOUND` | "No label with this code". |
| `UNSUPPORTED` | "Not a Backroom label or product barcode". Other URLs are never opened. |

Typed words that aren't a code also show matching items. `GET /api/v1/codes/{token}` serves the phone-camera route `/s/{token}`.

## Labels

`POST /organizations/{orgId}/labels.pdf` takes `{ targets: [{type, id}], layout, startPosition }` and is limited to Owners and Managers. It creates any missing QR codes and returns a PDF. Nothing is stored. A batch can hold at most 105 labels.

| Layout | Sheet | Label | Grid |
| --- | --- | --- | --- |
| `A4_21` | A4 | 63.5 × 38.1 mm (Avery L7160 size) | 3 × 7, 7.2 mm side and 15.15 mm top margin |
| `LETTER_10` | US Letter | 4 × 2 in (Avery 5163 size) | 2 × 5, 5/32 in side and 0.5 in top margin |
| `SINGLE` | One label per page | 63.5 × 38.1 mm | for label printers |

- **Label content:** QR (error correction M, four-module quiet zone, vector-drawn), the name in up to two lines, one detail line (SKU and unit, or the parent location), and the code in monospace.
- **Start position:** lets you reuse a partly used sheet.
- **Calibration:** `GET /labels/calibration.pdf?layout=` draws every label outline and a 50 mm ruler.

**Before printing real labels:** set `BACKROOM_PUBLIC_URL` to Backroom's permanent HTTPS address. Labels keep that address for as long as they are on a shelf. The print screen warns while it is still `localhost`.

## Scanner

- **Chrome on Android:** uses the browser's native `BarcodeDetector`.
- **Safari on iPhone and desktop browsers without it:** use the `barcode-detector` polyfill (zxing-cpp compiled to WebAssembly). It is loaded only when the camera starts, and its `.wasm` is served from Backroom itself (no CDN).
- **Formats:** QR, EAN-13, EAN-8, UPC-A, UPC-E.
- **Behaviour:** scanning pauses after the first read and resumes with **Scan again**.
- **Errors:** camera denied, no camera, an insecure (non-HTTPS) page and a camera that fails to start each get their own message, with manual entry always available.

## Testing on iPhone (Safari)

Camera access needs HTTPS. To test from a phone on the same Wi-Fi:

1. Start the backend as usual. In `.env`, set `BACKROOM_PUBLIC_URL=https://<this computer's LAN IP>:5173`, then restart the backend.
2. `cd frontend`, then `$env:BACKROOM_DEV_HTTPS='1'; npm run dev` in PowerShell (or `BACKROOM_DEV_HTTPS=1 npm run dev`).
3. On the iPhone, open `https://<LAN IP>:5173`, accept the certificate warning once, and sign in.
4. Print the calibration page at 100% and check the 50 mm ruler. Then print an A4 or Letter sheet for a few items and locations.

Record each check:

| Check | Expected | Result |
| --- | --- | --- |
| iPhone Camera app on a QR label | Notification opens Backroom on the right item or location (after sign-in if needed) | |
| Scan tab, QR label, arm's length | Result in under 2 seconds | |
| Scan tab, UPC-A on a product | Item opens, or "isn't linked yet" | |
| Scan tab, EAN-13 on a product | Same | |
| Damaged or partly covered QR | Reads, or manual code entry works | |
| Dim light / glossy label | Reads, or a clear prompt to try again | |
| Deny camera, then allow in Settings | Clear message, then works after allowing | |
| Record usage from a scan result | Sheet opens; nothing changes until submitted | |

If the polyfill is slow or unreliable on the iPhone, switch the fallback to `@zxing/browser` before shipping. It only affects `createDetector` in `frontend/src/scan.tsx`.

## Verification

- **Unit tests:** GTIN normalization and check digits (including UPC-E expansion), token generation and parsing, link recognition, label pagination, and a PDF round trip that decodes the printed QR back to its URL.
- **`labels.LabelsIntegrationTests`:**
  - codes are reused and survive renames and moves;
  - revocation is permanent and the database guards it;
  - barcode rules and role limits;
  - every scan status, including privacy across businesses;
  - QR-link lookups;
  - label PDFs, their limits and the calibration page.
- **`frontend/e2e/scanning.spec.ts`:**
  - a signed-out phone opens a label link, signs in and lands on the location;
  - manual lookup by code, by unlinked barcode (then linking it) and by item name;
  - Chromium's fake camera showing a QR is read by the bundled fallback and pauses on the result;
  - Record usage opens the confirmation sheet;
  - no sideways scrolling at 390 px.
