# Phase 3 — Real BLE Link Layer

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** two physical Android phones discover each other over BLE, connect, and move frame bytes between them. Nothing above the Link layer changes.

**Architecture:** a Kotlin implementation of the `Link` interface from Phase 2 — advertiser, scanner, GATT server, GATT client — exposed through the `moshgrid-core` Expo Module. `RealMeshEngine` is reused unchanged.

**Tech Stack:** Kotlin, Android BLE (`BluetoothLeAdvertiser`, `BluetoothLeScanner`, `BluetoothGattServer`), Expo Modules API, JUnit, Robolectric.

**Spec:** [`../docs/design.md`](../docs/design.md) §4 (defects 5, 6), §5.1

**Devices:** **2 physical Android phones required.** Emulators have no usable BLE radio.

**Depends on:** Phase 2 complete, including the seam check.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Enforced directly here:

- **Legacy BLE advert carries 6 usable bytes.** Presence beacon only (defect 5).
- **`ADVERTISE_MODE_BALANCED` is 250 ms.** Named constants only, never magic numbers (defect 6).
- **Extended advertising is queried at runtime**, never assumed.
- **Only `src/mesh/index.ts` changes in the TypeScript layer.** Anything under `src/app/` is off limits.

## File structure

| File | Responsibility |
|---|---|
| `modules/moshgrid-core/android/.../ble/BleConstants.kt` | UUIDs, intervals, MTU, byte budgets |
| `modules/moshgrid-core/android/.../ble/AdvertPayload.kt` | Pack/unpack the 6-byte beacon |
| `modules/moshgrid-core/android/.../ble/BleAdvertiser.kt` | Advertising |
| `modules/moshgrid-core/android/.../ble/BleScanner.kt` | Scanning, peer table |
| `modules/moshgrid-core/android/.../ble/GattServer.kt` | Inbound connections, frame receive |
| `modules/moshgrid-core/android/.../ble/GattClient.kt` | Outbound connections, frame send |
| `modules/moshgrid-core/android/.../ble/BleLinkService.kt` | Ties them together, owns state |
| `modules/moshgrid-core/android/.../MoshgridCoreModule.kt` | Events and functions to TS |
| `src/mesh/ble/BleLink.ts` | `Link` implementation over the module |
| `src/mesh/index.ts` | One-line change to select `BleLink` |
| `scripts/harness/discovery.mjs` | Two-device discovery measurement |
| `scripts/harness/throughput.mjs` | Two-device throughput measurement |

---

## Task 1: Constants and the advert payload

Closes defects 5 and 6.

**Files:**
- Create: `ble/BleConstants.kt`, `ble/AdvertPayload.kt`
- Test: `ble/AdvertPayloadTest.kt`

**Interfaces:**
- Produces: `AdvertPayload.pack(deviceIdPrefix: ByteArray, flags: Int): ByteArray` (exactly 6 bytes) and `AdvertPayload.unpack(bytes: ByteArray): AdvertPayload?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// ble/AdvertPayloadTest.kt
package com.moshgrid.core.ble

import org.junit.Assert.*
import org.junit.Test

class AdvertPayloadTest {

    /**
     * Defect 5: the source specification budgeted 15 bytes of fields after a
     * 128-bit service UUID inside a 31-byte legacy advertisement. Once the AD
     * element headers are counted only 6 bytes remain.
     */
    @Test
    fun test_legacy_advert_fits_in_six_bytes() {
        val packed = AdvertPayload.pack(byteArrayOf(1, 2, 3, 4), flags = 0b10101)
        assertEquals(BleConstants.LEGACY_USABLE_BYTES, packed.size)
        assertEquals(6, packed.size)
    }

    @Test
    fun `pack then unpack round-trips`() {
        val packed = AdvertPayload.pack(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34), 0b01011)
        val unpacked = AdvertPayload.unpack(packed)!!
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x12, 0x34), unpacked.deviceIdPrefix)
        assertEquals(0b01011, unpacked.flags)
    }

    @Test
    fun `rejects a device id prefix that is not four bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdvertPayload.pack(byteArrayOf(1, 2, 3), 0)
        }
    }

    @Test
    fun `unpack returns null for the wrong length`() {
        assertNull(AdvertPayload.unpack(ByteArray(5)))
        assertNull(AdvertPayload.unpack(ByteArray(7)))
    }

    /** The budget arithmetic itself, asserted so it cannot silently drift. */
    @Test
    fun `legacy advertisement budget arithmetic holds`() {
        val flags = 3
        val serviceUuid = 2 + 16
        val manufacturerHeader = 4
        val usable = BleConstants.LEGACY_TOTAL_BYTES - flags - serviceUuid - manufacturerHeader
        assertEquals(BleConstants.LEGACY_USABLE_BYTES, usable)
    }

    /** Defect 6: 211 ms is not an Android preset. BALANCED is 250 ms. */
    @Test
    fun test_advertise_interval_uses_named_constant() {
        assertEquals(250, BleConstants.ADVERTISE_INTERVAL_BALANCED_MS)
    }
}
```

- [ ] **Step 2: Run and watch them fail**

```bash
cd android && ./gradlew :moshgrid-core:testDebugUnitTest --tests '*AdvertPayloadTest*'
```

- [ ] **Step 3: Implement**

```kotlin
// ble/BleConstants.kt
package com.moshgrid.core.ble

import java.util.UUID

object BleConstants {
    // Generate once with `uuidgen`, then never change it.
    val SERVICE_UUID: UUID = UUID.fromString("6d6f7368-6772-4964-9a01-000000000001")
    val FRAME_CHARACTERISTIC: UUID = UUID.fromString("6d6f7368-6772-4964-9a01-000000000002")
    val DIGEST_CHARACTERISTIC: UUID = UUID.fromString("6d6f7368-6772-4964-9a01-000000000003")

    const val MANUFACTURER_ID = 0xFFFF   // 0xFFFF is reserved for testing

    // Legacy advertisement budget — see docs/design.md §4, defect 5.
    const val LEGACY_TOTAL_BYTES = 31
    const val LEGACY_USABLE_BYTES = 6
    const val DEVICE_ID_PREFIX_BYTES = 4
    const val FLAGS_BYTES = 2

    // Android advertising presets — defect 6. Never write a raw interval.
    const val ADVERTISE_INTERVAL_LOW_LATENCY_MS = 100
    const val ADVERTISE_INTERVAL_BALANCED_MS = 250
    const val ADVERTISE_INTERVAL_LOW_POWER_MS = 1000

    const val REQUESTED_MTU = 517
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val PEER_STALE_AFTER_MS = 30_000L

    // Flag bit positions inside the 2 flag bytes.
    const val FLAG_HAS_PENDING = 0
    const val FLAG_ACCEPTING = 1
    const val FLAG_BUFFER_FULL = 2
    const val FLAG_EXTENDED_CAPABLE = 3
}
```

```kotlin
// ble/AdvertPayload.kt
package com.moshgrid.core.ble

data class AdvertPayload(val deviceIdPrefix: ByteArray, val flags: Int) {
    companion object {
        fun pack(deviceIdPrefix: ByteArray, flags: Int): ByteArray {
            require(deviceIdPrefix.size == BleConstants.DEVICE_ID_PREFIX_BYTES) {
                "device id prefix must be ${BleConstants.DEVICE_ID_PREFIX_BYTES} bytes, " +
                    "got ${deviceIdPrefix.size}"
            }
            return byteArrayOf(
                *deviceIdPrefix,
                (flags and 0xFF).toByte(),
                ((flags shr 8) and 0xFF).toByte(),
            )
        }

        fun unpack(bytes: ByteArray): AdvertPayload? {
            if (bytes.size != BleConstants.LEGACY_USABLE_BYTES) return null
            val prefix = bytes.copyOfRange(0, BleConstants.DEVICE_ID_PREFIX_BYTES)
            val flags = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            return AdvertPayload(prefix, flags)
        }
    }
}
```

A 4-byte prefix is not a full DeviceId. It narrows the candidate set so the scanner knows whether to connect; the full 8-byte id and the Ed25519 key are exchanged over GATT and verified there. Collisions are expected and handled, not prevented.

- [ ] **Step 4: Run and watch them pass**

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add BLE constants and 6-byte advert payload

Closes defects 5 and 6."
```

---

## Task 2: Permissions

Android 12+ split Bluetooth permissions, and getting this wrong produces silent scan failures rather than errors — the single most common cause of "my BLE app finds nothing."

**Files:**
- Modify: `app.json`
- Create: `src/lib/permissions.ts`
- Test: `src/lib/__tests__/permissions.test.ts`

**Interfaces:**
- Produces: `requiredBlePermissions(sdkInt: number): string[]`, `ensureBlePermissions(): Promise<boolean>`.

- [ ] **Step 1: Write the failing test**

```typescript
// src/lib/__tests__/permissions.test.ts
import { requiredBlePermissions } from '../permissions';

test('Android 12+ needs the split bluetooth permissions', () => {
  const perms = requiredBlePermissions(31);
  expect(perms).toContain('android.permission.BLUETOOTH_SCAN');
  expect(perms).toContain('android.permission.BLUETOOTH_ADVERTISE');
  expect(perms).toContain('android.permission.BLUETOOTH_CONNECT');
});

test('Android 11 and below need location instead', () => {
  const perms = requiredBlePermissions(30);
  expect(perms).toContain('android.permission.ACCESS_FINE_LOCATION');
  expect(perms).not.toContain('android.permission.BLUETOOTH_SCAN');
});
```

- [ ] **Step 2: Implement and add to `app.json`**

```json
"android": {
  "package": "com.moshgrid.app",
  "permissions": [
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
  ]
}
```

Declare `neverForLocation` on the scan permission in the manifest only if location is genuinely never derived from scan results. Phase 6 shares location, so leave it off and explain the permission to the user honestly at the prompt.

- [ ] **Step 3: Add a permissions row to Diagnostics** showing each permission and whether it is granted. This is the first thing to check when discovery fails.

- [ ] **Step 4: Commit**

---

## Task 3: Advertiser and scanner

**Files:**
- Create: `ble/BleAdvertiser.kt`, `ble/BleScanner.kt`
- Test: `ble/BleScannerTest.kt` (peer-table logic under Robolectric)

**Interfaces:**
- Produces: `BleAdvertiser.start(payload, mode)`, `BleAdvertiser.stop()`; `BleScanner.start(onPeer)`, `BleScanner.stop()`, and a `PeerTable` with staleness eviction.

- [ ] **Step 1: Write the failing peer-table tests**

```kotlin
@Test fun `peer table records rssi and last seen`() { /* ... */ }
@Test fun `peer table evicts entries older than PEER_STALE_AFTER_MS`() { /* ... */ }
@Test fun `peer table keeps the strongest rssi within a debounce window`() { /* ... */ }
```

The peer table is pure logic and must be tested without a radio. Everything touching `BluetoothLeScanner` directly stays in a thin uncovered shell.

- [ ] **Step 2: Implement the advertiser**

```kotlin
val settings = AdvertiseSettings.Builder()
    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)  // 250 ms, see defect 6
    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
    .setConnectable(true)
    .build()

val data = AdvertiseData.Builder()
    .setIncludeDeviceName(false)          // the name alone would blow the 31-byte budget
    .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
    .addManufacturerData(BleConstants.MANUFACTURER_ID, AdvertPayload.pack(prefix, flags))
    .build()
```

`setIncludeDeviceName(false)` is load-bearing. A device name of "Gagan's Pixel" is 14 bytes plus a 2-byte header and would overflow the advertisement, causing `ADVERTISE_FAILED_DATA_TOO_LARGE` — which is exactly the class of error defect 5 describes.

- [ ] **Step 3: Implement the scanner** with `ScanSettings.SCAN_MODE_BALANCED` and a filter on `SERVICE_UUID`. Filtering in the scan settings rather than in code matters: an unfiltered scan wakes the app for every BLE device at a festival.

- [ ] **Step 4: Two-device manual check**

Install on both phones, open Diagnostics on both. Each must list the other under peers with a plausible RSSI (roughly −40 to −70 at arm's length).

- [ ] **Step 5: Commit**

---

## Task 4: GATT server and client

**Files:**
- Create: `ble/GattServer.kt`, `ble/GattClient.kt`, `ble/BleLinkService.kt`
- Test: `ble/ChunkerTest.kt`

**Interfaces:**
- Produces: `BleLinkService.sendTo(peerAddress, bytes)`, `BleLinkService.onFrame(cb)`, `BleLinkService.setLocalDigest(bloom)`.

- [ ] **Step 1: Write the failing chunker tests**

A 4096-byte frame does not fit one GATT write. Chunking is pure logic and must be tested off-device.

```kotlin
@Test fun `splits a frame into chunks that fit the negotiated MTU`() { /* ... */ }
@Test fun `reassembles chunks back into the original frame`() { /* ... */ }
@Test fun `rejects a reassembly whose declared length does not match`() { /* ... */ }
@Test fun `discards a partial reassembly after the timeout`() { /* ... */ }
@Test fun `rejects a declared length above MAX_FRAME_BYTES`() { /* ... */ }
```

The last two matter for safety: without them a hostile peer pins memory by starting reassemblies it never finishes, or declares a 4 GB frame.

- [ ] **Step 2: Implement chunking** with a 4-byte header per chunk: 2-byte total length, 1-byte chunk index, 1-byte chunk count. Reassembly buffers are keyed by peer and expire after 30 seconds.

- [ ] **Step 3: Implement the GATT server** — one service, a write-without-response frame characteristic, and a readable digest characteristic. Request MTU 517 on connect and **use whatever is actually granted**, never the requested value.

- [ ] **Step 4: Implement the GATT client** — connect, discover services, negotiate MTU, write chunks, disconnect. Serialise connections; Android's BLE stack handles roughly 4–7 concurrent GATT connections and degrades badly past that.

- [ ] **Step 5: Two-device byte transfer check**

Add a temporary Diagnostics button that sends 1 KB of known bytes to the first peer. The receiving phone shows the byte count and a checksum. Both must match.

- [ ] **Step 6: Commit**

---

## Task 5: BleLink — the TypeScript face

**Files:**
- Create: `src/mesh/ble/BleLink.ts`
- Modify: `src/mesh/index.ts` (one line)
- Test: `src/mesh/ble/__tests__/BleLink.test.ts`

**Interfaces:**
- Consumes: the module's events and functions.
- Produces: `class BleLink implements Link`. Nothing above it changes.

- [ ] **Step 1: Implement `BleLink`** as a thin adapter over module events. It contains no logic beyond translating event shapes — logic here would be untestable off-device.

- [ ] **Step 2: Run the Phase 2 contract suite against it**

```typescript
describeMeshEngineContract('RealMeshEngine over BleLink', async () => {
  const id = (await loadOrCreateIdentity()).deviceId;
  return new RealMeshEngine(id, new BleLink());
});
```

This is the payoff for writing the contract suite in Phase 2 — the real engine is held to the same standard as the fake, with no new test code.

- [ ] **Step 3: Commit**

---

## Task 6: Measurement harnesses

Fills the ledger. No number is stated anywhere without one of these behind it.

**Files:**
- Create: `scripts/harness/discovery.mjs`, `scripts/harness/throughput.mjs`

- [ ] **Step 1: Discovery harness** — on two phones, clear state, start both, and record milliseconds from start to first peer appearing. Twenty runs; report median and 90th percentile.

```bash
npm run harness:discovery
```

- [ ] **Step 2: Throughput harness** — send 100 KB in 4 KB frames between two phones, record wall time, report bytes/sec.

```bash
npm run harness:throughput
```

- [ ] **Step 3: Record both in the ledger.** If throughput is below roughly 2 KB/s, stop and tune MTU, connection interval, and chunk size before Phase 4 — Phase 4's design depends on knowing this number.

- [ ] **Step 4: Commit**

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → all exit 0
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass, including `test_legacy_advert_fits_in_six_bytes` and `test_advertise_interval_uses_named_constant`
- [ ] Contract suite passes against `BleLink` on a real phone
- [ ] **Two phones running the app each list the other in Diagnostics with a live RSSI that changes as you walk apart** ← the gate
- [ ] 1 KB transfers between phones with a matching checksum
- [ ] Discovery and throughput rows filled in the ledger
- [ ] Permissions row in Diagnostics shows all granted

## Re-check before moving on

- [ ] Re-run Phases 0–2 exit gates. All still green.
- [ ] **Seam check:** `git diff --stat <phase-2-tag>..HEAD -- src/app/` → **empty**. Any change here means the interface was wrong.
- [ ] Test on both phones with the roles swapped — whichever advertises first must not matter.
- [ ] Turn Bluetooth off on one phone mid-session. The other must show the peer going stale, not crash.
- [ ] Check `adb logcat | grep -i "ADVERTISE_FAILED"` → no occurrences.

## Updating the index

- [ ] Tick defects 5 and 6 in the [defect tracker](README.md#defect-regression-tracker)
- [ ] Fill **BLE GATT throughput** and **Time to first peer discovery** in the ledger
- [ ] Set Phase 3 to `☑ Done`

## What Phase 4 relies on

| Produced here | Used by |
|---|---|
| `BleLink` | Phase 4 runs the real mesh over it |
| Measured throughput | Phase 4 sizes sync batches and picks L |
| `PeerTable` staleness | Phase 4 peer selection |
| Chunker | Phase 4 full-size frame transfer |
