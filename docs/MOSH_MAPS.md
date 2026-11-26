# MoshGrid maps

Find's map has three sources, picked in this order:

1. **Built-in venue maps** (`app/src/main/assets/venues/`). Drawn from bundled GeoJSON on a
   background-only style. They never touch the network.
2. **Saved street maps.** OpenFreeMap's dark style saved as a MapLibre offline region. Used
   when a pin on the map falls inside a saved area. These work with no signal, and with
   MoshGrid's Internet switch off.
3. **Live street map.** The same OpenFreeMap style loaded over the network. Only used when
   Internet is on.

If none of these apply, the map says so and points to the compass and to Offline maps.

## Saving a map

Open You, then Offline maps (or Find's menu, or "Save maps for offline" on the no-map card).
Move and zoom to the area, then tap "Save this area".

- **What gets saved.** The whole style (style JSON, sprites, glyphs) and every tile from zoom
  0 to 14 for the visible box. OpenFreeMap's vector tiles stop at 14, and closer views reuse
  those tiles.
- **Size limit.** At most `OfflineMapMath.MAX_TILES` (1,500) tiles, about a 90 km square at
  Indian latitudes. A city area is usually a few to tens of MB. Larger boxes are refused with
  "Too big to save: zoom in".
- **Interrupted saves.** If the app closes or signal drops, a save stops part way. It shows as
  "Stopped at N%" with a Continue button.
- **Storage.** Maps sit in MapLibre's database in the app's private storage. Panic wipe resets
  that database, which removes saved maps and the map cache.

## Privacy

Saving a map, like viewing the live street map, tells OpenFreeMap the phone's internet address
and the area. The screen says this before saving. Once saved, the map draws from the phone.

With the Internet switch off, MoshGrid calls `MapLibre.setConnected(false)`. MapLibre then makes
no network requests and reads only saved maps and its cache.

Code: `mosh/OfflineMaps.kt`, `mosh/OfflineMapMath.kt` (tested in `OfflineMapMathTest`),
`ui/mosh/OfflineMapsUi.kt`, and `VenueMap` in `ui/mosh/FindScreen.kt`.
