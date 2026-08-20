# Phase 7 — Offline Map

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** a real map of the festival site, rendering with the phone in aeroplane mode, with your friends' live positions on it.

**Architecture:** MapLibre Native with a **local MBTiles file** registered through a custom tile source in the Kotlin module. Tiles are generated from an OpenStreetMap extract with Planetiler. No tile server, no API key, no network.

**Tech Stack:** `@maplibre/maplibre-react-native`, MapLibre Native Android, Planetiler, Kotlin.

**Spec:** [`../docs/design.md`](../docs/design.md) §3.6

**Devices:** 2 phones.

**Depends on:** Phase 6 complete.

## Why MBTiles and not PMTiles

MapLibre Native on Android supports the `pmtiles://` protocol, but **PMTiles sources do not support offline packs or caching** — they require live HTTP range requests, which fails at exactly the moment this app is supposed to work.

`OfflineManager.createPack()` was the other candidate. It works, but it downloads from a live style URL, needs network at pack-creation time, and makes a tile vendor's terms of service part of the product.

A local MBTiles file has none of those properties. One file, guaranteed offline, no provider, no key, no rate limit, and nothing that can change under you later. The cost is a custom tile source in Kotlin, which is incremental work given the module already exists.

## Global constraints

All from [`README.md`](README.md#global-constraints). Enforced here:

- **No network access is required to render the map.** Tested in aeroplane mode.
- Map assets must be permissively licensed. OpenStreetMap data is ODbL — attribution is **required and must be visible on the map**.
- Only contacts actively sharing location appear as pins.

## File structure

| File | Responsibility |
|---|---|
| `tools/build-festival-pack.sh` | OSM extract → MBTiles via Planetiler |
| `modules/.../map/LocalTilesSource.kt` | Serves MBTiles to MapLibre |
| `modules/.../map/MbtilesReader.kt` | SQLite reader for the MBTiles format |
| `src/map/festivalPack.ts` | Pack install, verify, select |
| `src/app/map.tsx` | Map screen |
| `assets/styles/festival.json` | MapLibre style referencing the local source |

---

## Task 1: Generate a festival pack

**Files:**
- Create: `tools/build-festival-pack.sh`, `tools/README.md`

- [ ] **Step 1: Install Planetiler** (Java, Apache-2.0) and download a regional OSM extract from Geofabrik.

- [ ] **Step 2: Write the script** taking a bounding box and an output name, producing an `.mbtiles` file at zooms 12–17. Zoom 17 is enough to see individual stages and tents; 18+ multiplies size for little gain outdoors.

```bash
./tools/build-festival-pack.sh --bbox="-2.60,51.13,-2.55,51.17" --name=glastonbury
```

- [ ] **Step 3: Check the size.** A festival-sized bounding box at z12–17 should land in single-digit megabytes. If it is above roughly 50 MB, the bbox or the zoom range is wrong — a pack nobody will download is a pack that does not work.

- [ ] **Step 4: Record the size and generation command** in `tools/README.md` so the pack is reproducible rather than a mystery binary in the repo.

- [ ] **Step 5: Commit** the script. **Do not commit the `.mbtiles` file** — it is a build artefact and will bloat the repository permanently.

---

## Task 2: MBTiles reader

**Files:**
- Create: `map/MbtilesReader.kt`
- Test: `map/MbtilesReaderTest.kt`

**Interfaces:**
- Produces: `MbtilesReader.tile(z: Int, x: Int, y: Int): ByteArray?`, `MbtilesReader.metadata(): Map<String, String>`.

- [ ] **Step 1: Write the failing tests** against a tiny checked-in fixture (a few tiles, kilobytes, committed deliberately as test data):

```kotlin
@Test fun `reads a known tile`() { /* ... */ }
@Test fun `returns null for a tile outside the pack`() { /* ... */ }
@Test fun `FLIPS THE Y COORDINATE between TMS and XYZ schemes`() {
    // MBTiles stores TMS (origin bottom-left); MapLibre requests XYZ (top-left).
    // Getting this wrong renders a vertically mirrored world — and it looks
    // plausible enough at a glance to survive a casual check.
}
@Test fun `reads metadata including bounds and zoom range`() { /* ... */ }
@Test fun `rejects a corrupt or non-mbtiles file with a clear error`() { /* ... */ }
```

The Y-flip test is the one that saves a day. `y_tms = (1 << z) - 1 - y_xyz`.

- [ ] **Step 2: Implement** as a read-only SQLite reader over the `tiles` and `metadata` tables. Open once and cache the handle; opening per tile is unusably slow while panning.

- [ ] **Step 3: Commit**

---

## Task 3: Register the source with MapLibre

**Files:**
- Create: `map/LocalTilesSource.kt`
- Modify: `MoshgridCoreModule.kt`

- [ ] **Step 1: Implement** a custom source that MapLibre queries for tiles, backed by `MbtilesReader`. Serve tiles off the main thread — tile reads on the UI thread produce visible stutter while panning.

- [ ] **Step 2: Expose `installFestivalPack(path)` and `activeFestivalPack()`** to TypeScript.

- [ ] **Step 3: Write the style**

```json
// assets/styles/festival.json — sources point at the local scheme only.
// Any http(s) URL in this file is a bug: it will silently fail offline,
// which is the exact failure this phase exists to prevent.
```

- [ ] **Step 4: Add a test** asserting the style file contains no `http://` or `https://` URLs. This is cheap and catches the most likely regression.

- [ ] **Step 5: Commit**

---

## Task 4: Map screen

**Files:**
- Create: `src/app/map.tsx`, `src/map/festivalPack.ts`

- [ ] **Step 1: Install**

```bash
npx expo install @maplibre/maplibre-react-native
```

Run the licence gate immediately afterwards — this pulls a large transitive tree and is the most likely place a copyleft dependency appears.

```bash
npm run check:licences
```

- [ ] **Step 2: Implement the screen** — the local style, your own position with an accuracy halo, and pins for contacts sharing location. Pin colour encodes fix age: fresh under 2 minutes, ageing 2–10, stale beyond.

- [ ] **Step 3: Show OpenStreetMap attribution visibly.** ODbL requires it, and it is not optional.

- [ ] **Step 4: Handle the no-pack case** with an explicit "no map downloaded for this festival" state and a download action — never a blank grey rectangle, which reads as a crash.

- [ ] **Step 5: Tapping a pin opens that contact's radar screen**, since radar is the better instrument for the last stretch.

- [ ] **Step 6: Commit**

---

## Task 5: Field test

- [ ] **Step 1: Install a pack, then put the phone in aeroplane mode with Bluetooth on.**

**Expected:** the map renders fully, pans and zooms smoothly, with no blank tiles at any zoom in range.

- [ ] **Step 2: Two phones, both in aeroplane mode, sharing location.** Each shows the other's pin moving as you walk.

- [ ] **Step 3: Pan to the pack's edge.** Beyond the bounds it must degrade cleanly to empty background, not blank tiles inside the covered area.

- [ ] **Step 4: Measure** cold map open time and memory. If opening takes more than about two seconds, cache the SQLite handle across screen mounts.

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass, including the Y-flip test
- [ ] Style file contains no remote URLs
- [ ] **Aeroplane mode, Bluetooth on: the map renders and shows your friend's live pin** ← the gate
- [ ] OpenStreetMap attribution visible
- [ ] Pack generation reproducible from `tools/README.md`

## Re-check before moving on

- [ ] Re-run Phases 0–6 exit gates. All green.
- [ ] **Seam check:** map is additive; chat and radar untouched.
- [ ] Confirm no network calls during map use: `adb shell dumpsys netstats` before and after panning, or run with WiFi and mobile data fully off.
- [ ] Delete the pack while the map is open. Clean empty state, no crash.
- [ ] Re-measure battery with the map open — it is GPU-heavy and should be recorded separately.

## Updating the index

- [ ] Add **Map cold open time** and **Battery with map open** rows to the ledger
- [ ] Set Phase 7 to `☑ Done`

## What Phase 8 relies on

| Produced here | Used by |
|---|---|
| Complete feature surface | Phase 8 attacks all of it |
| Pack install path | Phase 8 fuzzes malformed packs |
