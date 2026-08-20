# Phase 5 — Background Operation & Battery

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** the phone stays in the mesh with the screen off and the app closed, and you know the real battery cost because you measured it.

**Architecture:** a `ForegroundService` owning the BLE stack, plus `PendingIntent`-based scanning registered inside the Android Bluetooth process. The engine and UI are unchanged.

## What actually survives Doze — read this before designing anything here

An earlier draft of this phase claimed the design "survives Doze" and built a wake window on a `PARTIAL_WAKE_LOCK`. Both were wrong, and wrong in the direction that flatters the product:

- **Doze ignores wake locks.** Android's documentation lists it among Doze's restrictions verbatim. A design whose wake window depends on one has no wake window.
- **Android 12+ blocks starting a foreground service from the background.** Receiving a BLE scan `PendingIntent` is not among the exemptions, so the receiver cannot escalate into an FGS to do its work.

What is true is narrower and still useful: **the scan registration survives Doze; the app's ability to act on a result does not** — unless the user has granted the battery-optimisation exemption, which is exemption 13 on Android's list.

Two consequences that shape the whole phase:

1. **Task 4 (the exemption) is a prerequisite of Task 3, not a follow-up.** Without it, background delivery is best-effort at maintenance windows.
2. **Google Play policy restricts requesting that exemption directly** — "unless the core function of the app is adversely affected." A festival safety mesh is a defensible case, but the justification has to be written before submission, not after rejection.

The honest product claim is therefore: *messages arrive promptly in the background on devices where the user granted the exemption, and at maintenance windows otherwise.* Anything stronger is marketing the platform does not support.

**Tech Stack:** Kotlin (`ForegroundService`, `PendingIntent` scan, `BroadcastReceiver`, WakeLock), `dumpsys batterystats`.

**Spec:** [`../docs/design.md`](../docs/design.md) §3.4

**Devices:** 2 phones, ideally different manufacturers — OEM battery managers differ more than Android versions do.

**Depends on:** Phase 4 complete.

## Why this is a phase and not a detail

This is the difference between a demo and a product. A mesh that only relays while someone is staring at the screen is not a mesh — at a festival, every phone is in a pocket. It is also where the source PDF's numbers were least anchored: it asserted `<3%/hour passive` and `<7%/hour standard` with no measurement behind either, and derived them from a 211 ms advertising interval that is not an Android preset.

## Global constraints

All from [`README.md`](README.md#global-constraints). Enforced here:

- **No performance number ships unmeasured.** Every battery figure comes from `dumpsys batterystats`, recorded with its command — and labelled as the **model-derived estimate** it is, not as metered power.
- `ADVERTISE_MODE_BALANCED` is 250 ms; low-power mode uses the 1000 ms constant.

## File structure

| File | Responsibility |
|---|---|
| `modules/.../service/MeshForegroundService.kt` | Owns the BLE stack across app lifecycle |
| `modules/.../service/ScanReceiver.kt` | `BroadcastReceiver` for PendingIntent scan results |
| `modules/.../service/PowerPolicy.kt` | Mode → intervals mapping, pure logic |
| `modules/.../service/NotificationFactory.kt` | The persistent notification |
| `src/app/settings.tsx` | Power mode selector, OEM guidance |
| `scripts/harness/battery.mjs` | Battery measurement |

---

## Task 1: Power policy as pure logic

**Files:**
- Create: `service/PowerPolicy.kt`
- Test: `service/PowerPolicyTest.kt`

**Interfaces:**
- Produces: `PowerPolicy.forMode(mode: PowerMode, screenOn: Boolean): PowerSettings` with advertise interval, scan mode, scan window, and duty cycle.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `ultra power save uses the low power advertising constant`() {
    val s = PowerPolicy.forMode(PowerMode.ULTRA_POWER_SAVE, screenOn = false)
    assertEquals(BleConstants.ADVERTISE_INTERVAL_LOW_POWER_MS, s.advertiseIntervalMs)
}

@Test fun `standard mode with the screen off drops below the active interval`() { /* ... */ }
@Test fun `all in mode uses low latency only while the screen is on`() { /* ... */ }
@Test fun `every mode maps to a named constant, never a literal`() {
    for (mode in PowerMode.values()) {
        val s = PowerPolicy.forMode(mode, screenOn = false)
        assertTrue(s.advertiseIntervalMs in listOf(
            BleConstants.ADVERTISE_INTERVAL_LOW_LATENCY_MS,
            BleConstants.ADVERTISE_INTERVAL_BALANCED_MS,
            BleConstants.ADVERTISE_INTERVAL_LOW_POWER_MS,
        ))
    }
}
```

The last test is the structural guard for defect 6. Note it must run over **both** `screenOn` values — an earlier draft tested only `screenOn = false`, leaving the screen-on branch free to ship an invented interval. Asserting against the named constants rather than the literals `100, 250, 1000` matters too: literals in the test would be the same magic numbers the global constraint bans.

- [ ] **Step 2: Implement** four modes: `ULTRA_POWER_SAVE`, `POWER_SAVE`, `STANDARD`, `ALL_IN`. Keeping this pure means the whole battery strategy is unit-testable with no device.

- [ ] **Step 3: Commit**

---

## Task 2: The foreground service

**Files:**
- Create: `service/MeshForegroundService.kt`, `service/NotificationFactory.kt`

- [ ] **Step 1: Implement** with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`. Android 14+ rejects the service without a declared type.

- [ ] **Step 2: Write an honest notification.** It is permanently visible, so it should say something true and useful — "Connected to 3 people nearby" beats "MoshGrid is running". Include a Stop action; a service the user cannot stop gets the whole app uninstalled.

- [ ] **Step 3: Handle restart** — return `START_STICKY`, restore state from SQLite, and never assume the process survived.

- [ ] **Step 4: Verify** — open the app, press Home, confirm via `adb shell dumpsys activity services | grep -i moshgrid` that the service is running.

- [ ] **Step 5: Commit**

---

## Task 3: PendingIntent scanning

The mechanism that makes Android work where iOS cannot: the scan **registration** lives in the Bluetooth stack rather than the app process, so it outlives the process and is delivered through a manifest-declared `BroadcastReceiver`. What the app may then *do* with a result is constrained by Doze — see the Doze section above.

Three details that silently produce zero results if missed: the `PendingIntent` must wrap an **explicit** Intent, it must be **`FLAG_MUTABLE`** on Android 12+ (the system fills in the scan results), and registrations do **not** survive a reboot or a Bluetooth adapter off/on cycle.

**Files:**
- Create: `service/ScanReceiver.kt`
- Modify: `ble/BleScanner.kt`

- [ ] **Step 1: Implement** `startScan(filters, settings, pendingIntent)`. Register the receiver in the manifest, not at runtime — a runtime registration dies with the process, which defeats the purpose.

- [ ] **Step 2: Handle the wake window.** Doze ignores wake locks, so pick a path explicitly and record which, per Android version:
  - **Exempt from battery optimisation:** start the foreground service and sync normally. This is the only path with a real 30-second window.
  - **Not exempt, Android 12+:** `goAsync()` inside the receiver, which carries a hard budget of roughly 10 seconds — not 30 — or hand off to an expedited `WorkManager` job.
  - Never rely on a `PARTIAL_WAKE_LOCK` for the window. If one is taken for other reasons, **always release it in a `finally`**; a leaked wake lock destroys battery life and will not show up in a short test.

- [ ] **Step 3: Verify under Doze**

```bash
adb shell dumpsys battery unplug          # required: force-idle no-ops while charging
adb shell dumpsys deviceidle enable       # some devices ship with it disabled
# turn the screen off
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle | grep mState    # expect mState=IDLE — verify, never assume
# ... send a message from the other phone ...
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset           # omitting this corrupts the Task 5 measurements
```

Expected: the message arrives while the device is idle, or immediately on the next maintenance window. Record which.

- [ ] **Step 4: Commit**

---

## Task 4: OEM battery managers

**Files:**
- Modify: `src/app/settings.tsx`

- [ ] **Step 1: Detect the manufacturer** and show targeted guidance. Samsung, Xiaomi, Huawei, OnePlus and Oppo each kill background services differently and each hides the setting somewhere different.

- [ ] **Step 2: Deep-link to the battery optimisation exemption screen** with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and fall back to written instructions when the intent is unavailable.

- [ ] **Step 3: Add a Diagnostics row** showing whether the app is currently exempt. When background delivery mysteriously stops, this is the first thing to check.

- [ ] **Step 4: Test on every phone you own** and record which needed manual intervention.

- [ ] **Step 5: Commit**

---

## Task 5: Battery measurement

**Files:**
- Create: `scripts/harness/battery.mjs`

- [ ] **Step 1: Write the harness**

```bash
adb shell dumpsys batterystats --reset
# run for a fixed period in a fixed mode
adb shell dumpsys batterystats --charged com.moshgrid.app
```

Parse the app's mAh, convert to percent per hour against the battery's design capacity.

- [ ] **Step 2: Measure each mode for at least two hours**, screen off, both phones in range so the mesh is actually doing work. A measurement with no peers present measures nothing.

- [ ] **Step 3: Record every mode in the ledger** with the command that produced it.

  Do **not** set an absolute threshold. An earlier draft said "if `STANDARD` exceeds roughly 7%/hour, tune" — reintroducing the same unmeasured 7%/hour figure this phase opens by condemning the source PDF for asserting. The exit criterion is that a number exists, is reproducible, and is no worse than the previous phase's baseline.

  Note also that `dumpsys batterystats` reports a **model-derived estimate** from `power_profile.xml`, not a measurement. Say so in the ledger rather than presenting it as metered truth.

- [ ] **Step 4: Commit**

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass, including the named-constant guard
- [ ] **Close the app entirely on phone B. Send from phone A. The message arrives.** ← the gate
- [ ] Message delivered while the device is in forced Doze
- [ ] Battery measured for all four modes and recorded in the ledger
- [ ] The persistent notification says something true and has a working Stop action

## Re-check before moving on

- [ ] Re-run Phases 0–4 exit gates. All green.
- [ ] **Seam check:** no changes under `src/app/` beyond `settings.tsx` and Diagnostics rows.
- [ ] Reboot the phone. Confirm the service **restores whatever the user last chose**: mesh was on before the reboot → it comes back on; mesh was off → it stays off. Restoring a setting the user chose is not "starting without being asked"; silently dropping it is. A rebooted phone with no service and no scan registration is a dead node until someone unlocks it, which at a festival is the failure case. `ACTION_BOOT_COMPLETED` is an explicit exemption from the background-FGS-start restriction, and Android 15's BOOT_COMPLETED bans do not cover `connectedDevice`.
- [ ] Leave both phones idle overnight with the app closed. In the morning: service still running, battery drain within the measured figure, messages sent late at night delivered.
- [ ] Check for wake-lock leaks: `adb shell dumpsys power | grep -i wake` → no MoshGrid lock held while idle.

## Updating the index

- [ ] Fill **Battery drain, passive, %/hour** and add rows for the other three modes
- [ ] Set Phase 5 to `☑ Done`

## What later phases rely on

| Produced here | Used by |
|---|---|
| Foreground service | Phase 6 location updates in the background |
| `PowerPolicy` | Phase 6 adds a GPS duty cycle to it |
| Measured battery baseline | Phase 6 must not regress it |
