# Phase 9 — iOS

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

> **STATUS: BLOCKED.** No iOS device is available. Do not begin this phase until the unblock criteria below are met. Every task here is written against documented platform behaviour, not against something anyone has run.

**Goal:** iPhones join the mesh as **clients** — able to message, be messaged, and share location while the app is open — without ever being promised as relays.

**Architecture:** a Swift implementation of the Phase 2 `Link` interface, held to the same contract suite and the same Phase 8 attack suite. Nothing above the Link layer changes. The engine, protocol, storage and UI are already cross-platform.

**Tech Stack:** Swift, CoreBluetooth, Expo Modules API (Swift), ActivityKit (LiveActivity), XCTest.

**Spec:** [`../docs/design.md`](../docs/design.md) §3.4

**Devices:** at least one physical iPhone **plus** the two Android phones. The iOS Simulator has no Bluetooth radio, so it can test the UI and nothing else in this phase.

**Depends on:** Phases 3–5 complete and stable on Android, and Phase 8's attack suite passing.

## Why this phase is last, and honest about its ceiling

iOS cannot be a reliable mesh relay. That conclusion survives audit — but the reasons an earlier draft of this file gave for it did not, and the corrected reasons are stronger.

**The two hard constraints, both structural:**

1. **`CBPeripheralManager` cannot advertise manufacturer-specific data.** `startAdvertising` supports only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`. Phase 3's 6-byte presence beacon has nowhere to live, so an iOS device advertises a service UUID and nothing else — and Android's scanner needs a branch treating a missing manufacturer payload as "iOS peer, connect to identify" rather than as malformed.
2. **A backgrounded iPhone is largely invisible to Android.** Apple: service UUIDs that do not fit go to an overflow area "discoverable only by an iOS device explicitly scanning for them", and "while your app is in the background, the local name isn't advertised and all service UUIDs are in the overflow area."

**What the earlier draft got wrong, and how.** It claimed that from iOS 26 an app is relaunched for Bluetooth events *only* if it uses AccessorySetupKit, and that a force-quit **or restarted** iPhone cannot rejoin until manually reopened. TN3115 does not say that. The AccessorySetupKit note is attached to exactly two rows of its relaunch table:

| State | Relaunched? |
|---|---|
| Removed from memory by the system | **Yes** |
| App crashed | **Yes** |
| Device restarted | **Yes**, after first unlock |
| Force-quit by the user | No — AccessorySetupKit apps only |
| Control Centre Bluetooth toggled | Yes — AccessorySetupKit apps only |

That draft also said a backgrounded iPhone "scans for a limited window and then stops." With the `bluetooth-central` background mode it keeps scanning; Apple says only that "the interval at which your central device scans for advertising packets increases." Discovery gets slower, not dead. The ~30-second suspension applies to an app *without* the background mode.

The wrong version was near-verbatim a GitHub issue's paraphrase of TN3115 which had compressed the table into one sentence and dropped the row scoping. **Read the table, not the summary of the table** — and note this same paraphrase was what the project's original fact-check of the source PDFs relied on, so the error was inherited rather than invented here.

AccessorySetupKit does still block the mesh case where it applies: matching criteria must be declared ahead of time in `ASDiscoveryDescriptor`, and `showPicker` always requires human selection. There is no silent discovery of strangers' phones. But that governs relaunch after force-quit, not background BLE generally.

**What this means for the product:** iOS users get a working messenger that finds their friends when the app is open, which is the majority of what people actually want at a festival. They do not get background relaying, and marketing must never imply they do. The Android phones and, later, the deferred super-node hardware carry the mesh.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Added for this phase:

- **iOS is never described as a relay** in code comments, UI copy, or documentation.
- The Swift `Link` must pass the **unmodified** Phase 2 contract suite and the **unmodified** Phase 8 attack suite. A platform-specific carve-out in a security test is a security hole.
- No change to `src/app/`, `src/mesh/` above the Link layer, `src/crypto/`, or `src/db/`.

## Unblock criteria

Do not start until all four hold:

- [ ] At least one physical iPhone running iOS 26 or later is available for testing
- [ ] An Apple Developer account exists. **Not** for the reason an earlier draft gave — `UIBackgroundModes` is an Info.plist key, not an entitlement, so a free Personal Team profile plausibly does handle `bluetooth-central`; that claim was unsourced and is probably wrong. The real reasons are the **7-day expiry** of free-profile builds, which makes multi-day festival measurement impractical, and **ActivityKit push updates** (Task 4), which do need APNs
- [ ] Phases 3–5 are stable on Android — porting an unstable link doubles the debugging surface
- [ ] Phase 8's attack suite passes on Android, so any iOS failure is known to be iOS-specific

## File structure

| File | Responsibility |
|---|---|
| `crypto/scripts/build-crypto-ios.sh` | vodozemac → XCFramework via UniFFI |
| `modules/moshgrid-core/ios/MoshgridCoreModule.swift` | Module definition, events |
| `modules/moshgrid-core/ios/ble/BleConstants.swift` | Mirrors the Kotlin constants exactly |
| `modules/moshgrid-core/ios/ble/PeripheralManager.swift` | Advertising |
| `modules/moshgrid-core/ios/ble/CentralManager.swift` | Scanning, connections |
| `modules/moshgrid-core/ios/ble/Chunker.swift` | Mirrors the Kotlin chunker |
| `modules/moshgrid-core/ios/ble/BleLinkService.swift` | Ties them together |
| `modules/moshgrid-core/ios/activity/MeshActivity.swift` | LiveActivity |
| `docs/ios-limitations.md` | What iOS can and cannot do, for users and for sales |

---

## Task 1: vodozemac as an XCFramework

**Files:**
- Create: `crypto/scripts/build-crypto-ios.sh`
- Test: `crypto/tests/roundtrip.rs` re-run against the iOS targets

**Interfaces:**
- Produces: the same `MoshgridAccount` and `MoshgridSession` surface as Android, in Swift.

- [ ] **Step 0: Add `staticlib` to the crate type.** Phase 0 sets `crate-type = ["cdylib", "lib"]`, which never produces the `.a` this task's `xcodebuild` invocation consumes. Change it to `["staticlib", "cdylib", "lib"]` or the build below references a file that does not exist.

- [ ] **Step 1: Add the Apple targets**

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
```

- [ ] **Step 2: Build and package**

```bash
# crypto/scripts/build-crypto-ios.sh
set -euo pipefail
cd "$(dirname "$0")/.."

for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  cargo build --release --target "$target"
done

cargo run --features=uniffi/cli --bin uniffi-bindgen generate src/moshgrid_crypto.udl \
  --language swift --out-dir ../modules/moshgrid-core/ios/generated

# UniFFI emits *FFI.modulemap; Xcode only looks for module.modulemap.
mv ../modules/moshgrid-core/ios/generated/moshgrid_cryptoFFI.modulemap \
   ../modules/moshgrid-core/ios/generated/module.modulemap

# -headers is not optional in practice: without the FFI header and modulemap
# the XCFramework contains an unusable blob and the Swift import fails.
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libmoshgrid_crypto.a \
  -headers ../modules/moshgrid-core/ios/generated \
  -library target/aarch64-apple-ios-sim/release/libmoshgrid_crypto.a \
  -headers ../modules/moshgrid-core/ios/generated \
  -output ../modules/moshgrid-core/ios/MoshgridCrypto.xcframework
```

- [ ] **Step 3: Verify the identity is portable across platforms.** Generate an account on Android, pickle it, and unpickle it on iOS. The Ed25519 key must be identical. If it is not, the pickle format differs between builds and every cross-platform session will fail in a way that looks like a networking bug.

- [ ] **Step 4: Commit**

---

## Task 2: Swift constants mirroring Kotlin

**Files:**
- Create: `ios/ble/BleConstants.swift`
- Test: `ios/Tests/BleConstantsTests.swift`

- [ ] **Step 1: Write the failing tests**

```swift
func test_serviceUUIDMatchesAndroid() {
    XCTAssertEqual(
        BleConstants.serviceUUID.uuidString.lowercased(),
        "6d6f7368-6772-4964-9a01-000000000001"
    )
}

func test_legacyAdvertUsableBytesMatchesAndroid() {
    XCTAssertEqual(BleConstants.legacyUsableBytes, 6)
}

func test_maxFrameBytesMatchesAndroid() {
    XCTAssertEqual(BleConstants.maxFrameBytes, 4096)
}
```

A UUID mismatch between platforms produces two meshes that cannot see each other, with no error on either side. Pinning the literal in both languages is the cheapest possible defence against a full day of confused debugging.

- [ ] **Step 2: Add a cross-platform consistency check to CI** that parses both constant files and fails if any shared value diverges. Two hand-maintained copies drift; a test that compares them does not.

- [ ] **Step 3: Commit**

---

## Task 3: CoreBluetooth peripheral and central

**Files:**
- Create: `ios/ble/PeripheralManager.swift`, `ios/ble/CentralManager.swift`

- [ ] **Step 1: Implement advertising** with `CBPeripheralManager`.

**Platform note that shapes the design:** iOS gives far less control over advertisement contents than Android. `startAdvertising` accepts only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey` — **manufacturer-specific data cannot be advertised at all**. The 6-byte payload from Phase 3 has nowhere to live on iOS.

The consequence: an iOS device advertises its service UUID only. All identifying data moves to GATT after connection. Android's scanner must therefore treat a missing manufacturer payload as "an iOS peer, connect to identify" rather than as a malformed advert — and Phase 3's scanner needs that branch added.

**Backgrounded iOS is worse still:** the service UUID moves into an "overflow area" readable only by other iOS devices actively scanning for that specific UUID. An Android phone generally **cannot** see a backgrounded iPhone's advertisement. This is the single most important limitation to convey, because it looks exactly like a bug in the field.

- [ ] **Step 2: Implement scanning** with `CBCentralManager`, declaring the `bluetooth-central` background mode. Set `CBCentralManagerScanOptionAllowDuplicatesKey` to `false` — in the background iOS ignores it anyway, and in the foreground it burns battery for little gain.

- [ ] **Step 3: Implement the GATT server and client** mirroring Phase 3, with the same chunking format. Request MTU via `maximumWriteValueLength(for:)` and use what is granted, never a hard-coded value.

- [ ] **Step 4: Measure what actually survives backgrounding.** Foreground, backgrounded with the screen on, backgrounded with the screen off, and force-quit. Record the real behaviour in `docs/ios-limitations.md` rather than repeating anyone's documentation, this file included.

- [ ] **Step 5: Commit**

---

## Task 4: LiveActivity for extended foreground scanning

**Files:**
- Create: `ios/activity/MeshActivity.swift`

- [ ] **Step 1: Implement** an ActivityKit LiveActivity showing nearby friend count and unread messages, which keeps the app active on the Lock Screen and meaningfully extends scanning beyond a plain background.

- [ ] **Step 2: Be precise about the ceiling — and note that an earlier draft had this backwards in both directions.**

  ActivityKit grants **no** additional background execution. An app with a running Live Activity is suspended on the same schedule as any other; updates arrive from the foreground app, a BGTask, or an ActivityKit push. And `bluetooth-central` scanning does **not** stop when the screen sleeps.

  So build the Live Activity as **UI**, because a glanceable "3 friends nearby" is genuinely useful. Do not build it as a runtime extension, because it is not one.

- [ ] **Step 3: Make it genuinely useful** — showing "3 friends nearby" is worth the battery. Showing "MoshGrid is running" is not, and the user will dismiss it.

- [ ] **Step 4: Commit**

---

## Task 5: Run the Android test suites against iOS

The payoff for every interface decision made in Phase 2.

- [ ] **Step 1: Run the Phase 2 contract suite** against the Swift `Link`, unmodified.

```typescript
describeMeshEngineContract('RealMeshEngine over iOS BleLink', async () => {
  const id = (await loadOrCreateIdentity()).deviceId;
  return new RealMeshEngine(id, new BleLink());
});
```

- [ ] **Step 2: Run the Phase 8 attack suite**, unmodified. Every attack must fail on iOS exactly as it fails on Android. If one passes on iOS, the Swift link has a hole the Kotlin one does not.

- [ ] **Step 3: Cross-platform delivery test** — iPhone to Android and Android to iPhone, both apps open, aeroplane mode with Bluetooth on. Messages must arrive both directions.

- [ ] **Step 4: Cross-platform relay test** — Android relaying between two iPhones out of range of each other. This is the realistic topology: Android carries, iOS participates.

- [ ] **Step 5: Commit**

---

## Task 6: Tell the truth in the product

**Files:**
- Create: `docs/ios-limitations.md`
- Modify: `src/app/settings.tsx`

- [ ] **Step 1: Write the document** from measurement, not documentation. Include exactly what happens when an iPhone is force-quit, backgrounded, and screen-off, with the numbers you observed.

- [ ] **Step 2: Show an honest in-app status on iOS** — something like "Works best with the app open. iPhones cannot relay in the background." Users who understand a limitation forgive it; users surprised by it uninstall.

- [ ] **Step 3: Add an explicit note for any future sales material:** never present iOS and Android mesh participation as equivalent. The source PDF's own risk register reached this conclusion and it was right.

- [ ] **Step 4: Commit**

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] Swift unit tests pass, including the cross-platform constant checks
- [ ] CI constant-consistency check passes
- [ ] Phase 2 contract suite passes against the iOS link, unmodified
- [ ] Phase 8 attack suite passes against the iOS link, unmodified
- [ ] **A message travels from an iPhone to an Android phone, and back, in aeroplane mode with Bluetooth on** ← the gate
- [ ] An Android phone successfully relays between two out-of-range iPhones
- [ ] `docs/ios-limitations.md` reflects measured behaviour, not documentation
- [ ] iOS background behaviour measured in all four states and recorded

## Re-check before moving on

- [ ] Re-run Phases 0–8 exit gates on Android. All still green — adding iOS must not regress the platform that actually works.
- [ ] **Seam check:** `git diff --stat` for this phase touches only `modules/moshgrid-core/ios/`, `crypto/scripts/`, and documentation. Any change to `src/mesh/` above the Link layer means the interface was wrong.
- [ ] Force-quit the iPhone app, send from Android, reopen the iPhone app. The message must arrive on reopen, having been held by the Android relay.
- [ ] Confirm the Android scanner handles an iOS advert with no manufacturer data without logging it as malformed.
- [ ] Verify no UI copy anywhere claims background relaying on iOS.

## Updating the index

- [ ] Add **iOS background scan duration** and **iOS cross-platform delivery rate** rows to the ledger
- [ ] Set Phase 9 to `☑ Done` in [Progress](README.md#progress)
- [ ] Record in `plans/README.md` which iPhone model and iOS version the measurements came from — this behaviour changes between iOS releases and an undated measurement is worthless

## What comes after

V1 is complete at the end of this phase. The deferred work from `docs/design.md` §2 each needs its own spec and plan cycle: the backend and push notifications, medical dispatch, super-node relay hardware, festival schedule and weather, and group chat.

The super-node work is the highest-leverage of those, because always-on relay hardware is what makes iOS's ceiling stop mattering.
