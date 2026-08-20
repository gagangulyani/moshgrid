# Phase 6 — Location Sharing & Radar

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** point your phone and walk toward your friend. Bearing, distance, and a getting-warmer indicator, updating live over the mesh.

**Architecture:** location updates are just another payload type on the existing mesh — no new transport. The radar is a pure function of two coordinates and a compass heading, so it is fully unit-testable.

**Tech Stack:** `expo-location`, `expo-sensors` (magnetometer), React Native, fast-check.

**Spec:** [`../docs/design.md`](../docs/design.md) §2

**Devices:** 2 phones, outdoors for a real GPS fix.

**Depends on:** Phase 5 complete.

## Why radar and not only a map

Under about 50 metres in a crowd, a top-down map is the wrong instrument — you cannot tell which of the two blue dots is you, and GPS error is a large fraction of the distance. A compass arrow with a distance readout is what people actually use to close the last stretch. Phase 7 adds the map for the wider view; the two are complementary, and the radar is the one that works when the tiles have not downloaded.

## Global constraints

All from [`README.md`](README.md#global-constraints). Enforced here:

- Location sharing is **opt-in per contact**, off by default, and revocable.
- Location payloads ride the existing frame format — no new transport, no unencrypted side channel.
- The Phase 5 battery baseline must not regress by more than 2 %/hour.

## File structure

| File | Responsibility |
|---|---|
| `src/location/geo.ts` | Haversine distance, bearing, pure maths |
| `src/location/LocationService.ts` | GPS acquisition, duty cycle |
| `src/location/sharing.ts` | Per-contact opt-in state |
| `src/mesh/payloads.ts` | Payload type discrimination |
| `src/app/radar/[deviceId].tsx` | Radar screen |
| `src/app/settings.tsx` | Sharing toggles, auto-expiry |
| `src/db/locations.ts` | Latest known fix per contact |

---

## Task 1: Geo maths

Pure functions, no device, no permissions. Everything visual is built on these, so they get tested hardest.

**Files:**
- Create: `src/location/geo.ts`
- Test: `src/location/__tests__/geo.test.ts`

**Interfaces:**
- Produces: `distanceMetres(a, b)`, `bearingDegrees(from, to)`, `relativeBearing(bearing, heading)`, `formatDistance(m)`.

- [ ] **Step 1: Write the failing tests**

```typescript
test('distance between two known points matches a published value', () => {
  // London Eye to Big Ben ≈ 700 m. Use a real, checkable pair.
  expect(distanceMetres(
    { lat: 51.5033, lon: -0.1196 },
    { lat: 51.5007, lon: -0.1246 },
  )).toBeCloseTo(470, -2);
});

test('distance from a point to itself is zero', () => { /* ... */ });
test('bearing due north is 0 degrees', () => { /* ... */ });
test('bearing due east is 90 degrees', () => { /* ... */ });
test('relative bearing wraps correctly past 360', () => {
  expect(relativeBearing(10, 350)).toBe(20);
  expect(relativeBearing(350, 10)).toBe(-20);
});
test('formats sub-kilometre distances in metres', () => {
  expect(formatDistance(45)).toBe('45 m');
  expect(formatDistance(1250)).toBe('1.3 km');
});

test('property: distance is symmetric', () => { /* fast-check */ });
test('property: bearing there and back differs by 180 degrees', () => { /* fast-check */ });
```

The two property tests catch sign and wraparound errors, which are the bugs that make a radar arrow point confidently in exactly the wrong direction.

- [ ] **Step 2: Implement** with the haversine formula. Do not use a flat-earth approximation; it is wrong enough at festival scale to matter and costs nothing to avoid.

- [ ] **Step 3: Commit**

---

## Task 2: Location payloads on the mesh

**Files:**
- Create: `src/mesh/payloads.ts`, `src/db/locations.ts`
- Modify: `src/mesh/RealMeshEngine.ts`
- Test: `src/mesh/__tests__/payloads.test.ts`

**Interfaces:**
- Produces: `encodePayload(p: Payload): Uint8Array`, `decodePayload(b): Payload` where `Payload = { kind: 'message'; body: string } | { kind: 'location'; lat: number; lon: number; accuracy: number; at: number }`.
- Produces: `shareLocation(to, fix)` on `MeshEngine`.

- [ ] **Step 1: Write the failing tests**

```typescript
test('message and location payloads round-trip', () => { /* ... */ });
test('an unknown payload kind is rejected, not silently ignored', () => { /* ... */ });
test('location payloads are padded to the same bucket as short messages', () => {
  // Otherwise frame size alone reveals which kind it is to every relay.
});
test('a location payload from a contact who is not sharing is discarded', () => { /* ... */ });
```

The third test matters more than it looks. If location frames are a distinctive size, a passive observer learns who is sharing location with whom without decrypting anything — exactly the mesh-layer metadata leak that §3.3 exists to prevent.

- [ ] **Step 2: Implement.** Add the payload discriminator inside the encrypted body, never in the frame header.

- [ ] **Step 3: Commit**

---

## Task 3: Sharing consent

**Files:**
- Create: `src/location/sharing.ts`
- Modify: `src/db/schema.ts` (migration v3)
- Test: `src/location/__tests__/sharing.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
test('sharing is off by default for a new contact', () => { /* ... */ });
test('enabling sharing sets an expiry', () => { /* ... */ });
test('sharing auto-expires after the configured window', () => { /* ... */ });
test('revoking stops outbound location immediately', () => { /* ... */ });
test('a global off switch overrides every per-contact setting', () => { /* ... */ });
```

- [ ] **Step 2: Implement** with migration v3 adding `location_sharing` columns. Default 4-hour expiry. Append the migration; never edit v1 or v2.

- [ ] **Step 3: Commit**

---

## Task 4: GPS acquisition with a duty cycle

**Files:**
- Create: `src/location/LocationService.ts`
- Modify: `service/PowerPolicy.kt`

- [ ] **Step 1: Implement** with `expo-location`, requesting foreground permission first and background only if the user enables sharing while the app is closed. Asking for background location up front gets apps rejected and users alarmed.

- [ ] **Step 2: Duty-cycle by power mode** — extend `PowerPolicy` with a GPS interval per mode rather than inventing a second policy object. One place decides all power behaviour.

- [ ] **Step 3: Stop GPS entirely when no contact is being shared with.** The cheapest fix is not acquiring the fix at all.

- [ ] **Step 4: Commit**

---

## Task 5: The radar screen

**Files:**
- Create: `src/app/radar/[deviceId].tsx`
- Test: `src/app/__tests__/radar.test.tsx`

- [ ] **Step 1: Write the failing tests** against the pure logic — arrow rotation for a given bearing and heading, distance formatting, and the stale-fix state. Render tests, not device tests.

- [ ] **Step 2: Implement.** A large arrow rotating to `relativeBearing`, distance beneath it, and the fix age. When the fix is older than two minutes, show the age prominently and fade the arrow — a confident arrow pointing at where someone was ten minutes ago is worse than no arrow.

- [ ] **Step 3: Getting-warmer indicator** — compare the current distance with a short rolling window and show closer / further / holding. This is the part people actually use while walking.

- [ ] **Step 4: Haptic feedback under 20 metres** via `expo-haptics`, at which point they should look up rather than at the screen.

- [ ] **Step 5: Commit**

---

## Task 6: Field test

- [ ] **Step 1: Two phones, outdoors, 200 metres apart.** Enable sharing both ways. Walk toward each other.

**Expected:** both arrows point roughly at the other person, distance decreases monotonically apart from GPS jitter, haptics fire under 20 m.

- [ ] **Step 2: Repeat with cellular data off on both phones** — aeroplane mode with Bluetooth on. Everything must work identically. This is the actual product claim.

- [ ] **Step 3: Walk out of BLE range.** The arrow must go stale with a visible age rather than freezing on a stale value and looking live.

- [ ] **Step 4: Re-measure battery** in `STANDARD` with sharing on. Record it. If it regressed more than 2 %/hour over Phase 5, lengthen the GPS interval before proceeding.

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] All geo property tests pass
- [ ] **Two phones in aeroplane mode with Bluetooth on: the radar points at your friend and the distance updates as you walk** ← the gate
- [ ] Location sharing is off by default and auto-expires
- [ ] Battery with sharing on recorded; regression under 2 %/hour
- [ ] A stale fix is visibly stale, never silently stale

## Re-check before moving on

- [ ] Re-run Phases 0–5 exit gates. All green.
- [ ] **Seam check:** new screens added, existing chat screen untouched.
- [ ] Revoke sharing on one phone and confirm the other stops receiving updates within one sync interval.
- [ ] Confirm location frames are indistinguishable in size from message frames: capture both and compare lengths.
- [ ] Turn off GPS at the OS level. The app must degrade gracefully, showing "location unavailable" rather than crashing or hanging.

## Updating the index

- [ ] Add a **Battery with location sharing** row to the ledger
- [ ] Set Phase 6 to `☑ Done`

## What Phase 7 relies on

| Produced here | Used by |
|---|---|
| `distanceMetres`, `bearingDegrees` | Phase 7 pin placement |
| `src/db/locations.ts` | Phase 7 reads the same fixes |
| Sharing consent model | Phase 7 shows only shared contacts |
