# Phase 7 — Offline Map

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** a real map of the festival site, rendering with the phone in aeroplane mode, with your friends' live positions on it.

**Architecture:** a single local tile archive per festival, generated from an OpenStreetMap extract with Planetiler, addressed by a URL scheme MapLibre already supports. **No custom native code.**

**Tech Stack:** `@maplibre/maplibre-react-native`, Planetiler, `expo-asset` / `expo-file-system`.

**Spec:** [`../docs/design.md`](../docs/design.md) §3.6

**Devices:** 2 phones.

**Depends on:** Phase 6 complete.

## What this phase used to say, and why it changed

This phase was rewritten after audit. The original version was built on two claims, both wrong, and it is worth knowing what they were because the same mistakes are easy to repeat.

**1. "PMTiles needs live HTTP, so use MBTiles."** The quoted half — *"PMTiles sources do not support offline pack downloads or caching"* — is verbatim from MapLibre's docs. The conclusion welded onto it was invented. That restriction is about `OfflineManager`'s tile-pack database; it says nothing about local files, and `pmtiles://file://` is documented on the same page as reading "a file from device storage." PMTiles was never disqualified.

**2. "Register a custom `LocalTilesSource` in Kotlin."** There is no such class. MapLibre Android exposes `CustomGeometrySource`, `GeoJsonSource`, `ImageSource`, `RasterDemSource`, `RasterSource` and `VectorSource`; the only extension point takes parsed GeoJSON features, not tile bytes. Custom tile sources cannot be registered from Kotlin at all.

Both local schemes already ship inside the dependency, wired into its default resource loader:

```
mbtiles://file:///absolute/path/festival.mbtiles     (Android core since 9.3.0)
pmtiles://file:///absolute/path/festival.pmtiles     (alongside pmtiles://https://)
```

The deleted tasks would have hand-written, in Kotlin, through a non-existent API, code that the C++ core already contains — including the TMS/XYZ y-flip and the cached DB handle. **The correct amount of native code for this phase is zero.**

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Enforced here:

- **No network access is required to render the map.** Tested in aeroplane mode.
- Map assets permissively licensed. OpenStreetMap data is ODbL; attribution is required.
- Only contacts actively sharing location appear as pins.

## File structure

| File | Responsibility |
|---|---|
| `tools/build-festival-pack.sh` | OSM extract → tile archive via Planetiler |
| `tools/README.md` | Reproducible generation command and recorded sizes |
| `src/map/festivalPack.ts` | Pack install, verify, select, path resolution |
| `src/map/__tests__/festivalPack.test.ts` | Pack logic tests |
| `src/app/map.tsx` | Map screen |
| `assets/styles/festival.json` | Style referencing the local scheme only |

---

## Task 1: Verify the scheme works on a device — **GATE**

Do this before writing anything else. It decides the format and costs an afternoon at most.

**Files:**
- Create: `tools/spike-notes.md`

- [ ] **Step 1: Install the map library and re-run the licence gate**

```bash
npx expo install @maplibre/maplibre-react-native
npm run check:licences
```

Expected: exit 0. This pulls a large transitive tree and is the most likely place a copyleft dependency appears.

- [ ] **Step 2: Get any small tile archive onto the device**

Any `.mbtiles` or `.pmtiles` file will do — a public sample is fine for the spike. Push it and note the absolute path:

```bash
adb push sample.mbtiles /sdcard/Android/data/com.moshgrid.app/files/
```

- [ ] **Step 3: Point a style at it and render**

Minimal RN component, both schemes tried in turn:

```tsx
<MapLibreGL.MapView style={{ flex: 1 }}>
  <MapLibreGL.RasterSource
    id="local"
    tileUrlTemplates={['mbtiles:///sdcard/Android/data/com.moshgrid.app/files/sample.mbtiles']}
  >
    <MapLibreGL.RasterLayer id="local-layer" sourceID="local" />
  </MapLibreGL.RasterSource>
</MapLibreGL.MapView>
```

- [ ] **Step 4: Record which schemes actually work** in `tools/spike-notes.md`, with the installed `@maplibre/maplibre-react-native` version.

The schemes are wired into MapLibre's C++ core unconditionally, but whether they are compiled into the `.aar` bundled with **this** version of the React Native wrapper is the open question, and the only one that matters. **Do not proceed on a format that did not render in aeroplane mode on a real phone.**

- [ ] **Step 5: Commit the spike notes**

---

## Task 2: Generate a festival pack

**Files:**
- Create: `tools/build-festival-pack.sh`, `tools/README.md`

- [ ] **Step 1: Install Planetiler** (Java, Apache-2.0 — verified) and download a regional OSM extract from Geofabrik.

- [ ] **Step 2: Write the script** taking a bounding box and an output name.

```bash
./tools/build-festival-pack.sh --bbox="-2.60,51.13,-2.55,51.17" --name=glastonbury
```

**Zoom range is `--minzoom=12 --maxzoom=14`.** Two reasons, and the first is a hard blocker:

- **Planetiler caps maxzoom at 16.** `PlanetilerConfig.MAX_MAXZOOM = 16`, and a higher value throws `IllegalArgumentException` at startup. An earlier draft of this plan specified z12–17, which cannot run.
- **z14 is the OpenMapTiles convention and is enough.** Clients overzoom above 14 for free, so stage-level detail costs nothing extra in the pack.

- [ ] **Step 3: Check the size against a tripwire that can actually fire.**

Computed tile counts for the bbox above (≈3.5 × 4.4 km of rural Somerset):

| Zoom range | Tiles | Realistic size |
|---|---|---|
| 12–14 (this plan) | 17 | well under 1 MB |
| 12–16 (Planetiler max) | 182 | 1–3 MB |
| 12–17 (unbuildable) | 638 | 3–10 MB |

**Warn above 5 MB, not 50.** An earlier draft used 50 MB, which for 638 tiles implies 80 KB per tile — roughly an order of magnitude beyond plausible for this terrain. That tripwire could never fire, so the check verified nothing.

- [ ] **Step 4: Verify the pack contains something useful, not merely something renderable.**

Open it and confirm real features are present — paths, buildings, roads. Worthy Farm is largely farmland in OSM, and a basemap of an empty green field passes a "no blank tiles" check while being useless to a person trying to find a stage. This step exists because the render gate alone cannot catch it.

- [ ] **Step 5: Record the exact command and resulting size** in `tools/README.md` so the pack is reproducible rather than a mystery binary.

- [ ] **Step 6: Commit the script.** **Do not commit the archive** — it is a build artefact and will bloat the repository permanently.

---

## Task 3: Pack install and the style

**Files:**
- Create: `src/map/festivalPack.ts`, `assets/styles/festival.json`
- Test: `src/map/__tests__/festivalPack.test.ts`

**Interfaces:**
- Produces: `installPack(name): Promise<string>` returning the absolute on-device path, `activePackPath(): Promise<string | null>`, `listPacks(): Promise<PackInfo[]>`.

- [ ] **Step 1: Write the failing tests**

```typescript
// src/map/__tests__/festivalPack.test.ts
import { styleWithLocalSource, packUrl, NoPackError } from '../festivalPack';

test('builds a scheme URL from an absolute path', () => {
  expect(packUrl('/data/user/0/com.moshgrid.app/files/glastonbury.mbtiles'))
    .toBe('mbtiles:///data/user/0/com.moshgrid.app/files/glastonbury.mbtiles');
});

test('rejects a relative path, which renders as a blank map with no error', () => {
  expect(() => packUrl('glastonbury.mbtiles')).toThrow(/absolute/i);
});

test('THE STYLE CONTAINS NO REMOTE URLS', () => {
  // The whole phase fails silently if one http(s) source survives: it renders
  // fine on wifi during development and blanks in the field.
  const style = JSON.stringify(styleWithLocalSource('/tmp/x.mbtiles'));
  expect(style).not.toMatch(/https?:\/\//);
});

test('missing pack raises a typed error rather than returning an empty style', () => {
  expect(() => styleWithLocalSource(null as unknown as string)).toThrow(NoPackError);
});
```

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- festivalPack
```

Expected: FAIL — cannot find module `../festivalPack`.

- [ ] **Step 3: Implement.** Pure path and style construction, no native calls, so it is testable in Node — which matters given `expo-sqlite` and `expo-crypto` cannot run under jest (`DEFECTS.md` B1, B2).

- [ ] **Step 4: Run and watch them pass**

- [ ] **Step 5: Commit**

---

## Task 4: Map screen

**Files:**
- Create: `src/app/map.tsx`

- [ ] **Step 1: Implement the screen** — the local style, your own position with an accuracy halo, and pins for contacts sharing location. Pin colour encodes fix age: fresh under 2 minutes, ageing 2–10, stale beyond.

- [ ] **Step 2: Show attribution.** ODbL §4.3 requires a notice making anyone exposed to the work aware the data came from OpenStreetMap **and is available under ODbL** — the licence half is not optional, so "© OpenStreetMap contributors" alone is insufficient. Use:

```
© OpenStreetMap contributors, ODbL 1.0
```

The licence requires the notice be "reasonably calculated" to reach the viewer; it does not mandate text painted on the map surface, so a legal affordance reachable from the map view also satisfies it. Keeping it on-screen is this project's choice, not a legal minimum — state it that way rather than overclaiming.

- [ ] **Step 3: Handle the no-pack case** with an explicit "no map downloaded for this festival" state and a download action — never a blank grey rectangle, which reads as a crash.

- [ ] **Step 4: Tapping a pin opens that contact's radar screen**, since radar is the better instrument for the last stretch.

- [ ] **Step 5: Commit**

---

## Task 5: Field test

- [ ] **Step 1: Install a pack, then aeroplane mode with Bluetooth on.**

Expected: the map renders fully, pans and zooms smoothly, no blank tiles at any zoom in range.

- [ ] **Step 2: Two phones, both in aeroplane mode, sharing location.** Each shows the other's pin moving as you walk.

- [ ] **Step 3: Pan to the pack's edge.** Beyond the bounds it must degrade cleanly to empty background, not blank tiles inside the covered area.

- [ ] **Step 4: Measure** cold map open time and memory, and record both in the ledger.

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] **Task 1 spike recorded: at least one local scheme renders on a real device in aeroplane mode** ← the gate that decides the format
- [ ] `npm test -- festivalPack` → 4 passed, including the no-remote-URLs test
- [ ] Pack builds with `--maxzoom=14` and lands under the 5 MB tripwire
- [ ] Pack verified to contain real features, not empty farmland
- [ ] **Aeroplane mode, Bluetooth on: the map renders and shows your friend's live pin**
- [ ] Attribution names both OpenStreetMap and ODbL
- [ ] `tools/README.md` makes pack generation reproducible

## Re-check before moving on

- [ ] Re-run Phases 0–6 exit gates. All green.
- [ ] **Seam check:** map is additive; chat and radar untouched.
- [ ] Confirm no network calls during map use — run with WiFi and mobile data fully off, not merely unused.
- [ ] Delete the pack while the map is open. Clean empty state, no crash.
- [ ] Re-measure battery with the map open; it is GPU-heavy and belongs in the ledger separately.
- [ ] Confirm zero Kotlin was written for this phase. If any was, the URL-scheme path was abandoned and the reason needs recording.

## Updating the index

- [ ] Add **Map cold open time** and **Battery with map open** rows to the ledger
- [ ] Record the chosen tile format and the library version it was verified against
- [ ] Set Phase 7 to `☑ Done`

## What Phase 8 relies on

| Produced here | Used by |
|---|---|
| Complete feature surface | Phase 8 attacks all of it |
| `installPack` path handling | Phase 8 fuzzes malformed and hostile archives |
