# Backroom product demo

[Watch or download the film](out/backroom-demo.mp4). The 39-second silent film uses 1920 × 1080 H.264 video at 30 fps. The on-screen copy carries the story when the player is muted.

## Feature audit

The demo follows Juniper Coffee, a sample business created through Backroom’s API. Every product screen is a capture of the repository’s React frontend running against Spring Boot and PostgreSQL. The printed shelf label is a rasterization of the PDF that the app downloaded.

- **Inventory.** The catalog lists items, SKUs, units and on-hand totals. An item detail breaks the total down by location. Receipts, usage, waste, transfers, counts and reversals feed movement history. [Catalog](../frontend/src/catalog.tsx) · [Stock actions](../frontend/src/stock.tsx)
- **Storage.** Rooms, shelves, cabinets and bins can be nested. A location shows its children and the items stored there. [Locations](../frontend/src/locations.tsx)
- **Labels and barcodes.** Managers and owners can generate QR labels for items and locations and download them as a print-ready PDF. Items also support linked manufacturer UPC/EAN barcodes. Camera scanning and manual code entry resolve records. The film shows manual entry. [Label printing](../frontend/src/labels.tsx) · [Barcode linking](../frontend/src/codes.tsx) · [Lookup](../frontend/src/scan.tsx)
- **Equipment.** A brew scale is a catalog item with an equipment SKU and the base unit Each. Its count, placement and transfer use the same inventory workflow as supplies. [Catalog](../frontend/src/catalog.tsx) · [Supported units](../src/main/java/com/backroom/inventory/Unit.java)

## Storyboard

| Time | Picture and action |
| --- | --- |
| 0–4 s | Backroom’s mark and an angled catalog view introduce the two questions a stock check answers. |
| 4–11 s | Highlight House espresso in Inventory. Move into its item detail and show 14 kg split between the coffee shelf and the service shelf. |
| 11–17.5 s | Open Stockroom, then Coffee shelf. Its contents include 4 kg of decaf and 12 kg of house espresso. |
| 17.5–23 s | Show the single-label print option beside the QR label Backroom generated for Coffee shelf. |
| 23–28.5 s | Enter the linked product barcode. The result identifies House espresso and offers item and stock actions. |
| 28.5–36 s | Transfer one brew scale from the equipment shelf to the service shelf. Show the confirmation and one scale at each location. |
| 36–39 s | Hold on the Backroom mark and the repository’s tagline. |

## Preview and render

From the repository root, using Node 24 and npm:

```sh
cd video
npm ci
npm run studio
```

Select **BackroomDemo** in Studio. To export the MP4, run this from the same directory:

```sh
npm run render
```

The render writes [out/backroom-demo.mp4](out/backroom-demo.mp4). The package includes its captures and can render independently of a running Backroom instance. Remotion can download its headless browser on the first render. To use an installed Chromium executable, set the optional environment variable `REMOTION_BROWSER_EXECUTABLE` to its full path.

[Composition and timing](src/Demo.jsx) · [Registration and dimensions](src/index.jsx) · [Typography and visual treatment](src/film.css) · [Package scripts](package.json) · [Remotion render reference](https://www.remotion.dev/docs/cli/render)

## Refresh the app captures

This is optional for editing or rendering the film. Start a disposable Backroom backend/database and run the frontend from this checkout against it using the [app setup instructions](../README.md). The capture script creates a new sample account, business, stock balances and a transfer on each run.

1. Install the frontend’s locked dependencies and its Playwright Chromium browser. From `frontend/`, run `npm ci` then `npx playwright install chromium`.
2. Put Poppler’s `pdftoppm` on PATH to rasterize the generated label.
3. From `video/`, run `npm run capture`. The default app origin is `http://127.0.0.1:5173`. Set `BACKROOM_CAPTURE_URL` to use a different local development origin, and optionally set `CAPTURE_BROWSER` to select an installed Chromium executable.
4. Preview the refreshed shots in Studio and render. App-generated label tokens and sample account identifiers vary between captures.

[Sample data and capture script](scripts/capture.mjs) · [Captured assets](public/)
