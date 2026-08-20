# Phase 4 — Real Mesh Messaging

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** a message travels from phone A to phone B through phone C, over real Bluetooth, encrypted end to end, with C unable to read it.

**Architecture:** the digest-driven sync protocol on top of Phase 3's link. Everything above the Link layer is Phase 2 code, unchanged. This phase adds the sync conversation, wires the bloom digest into real advertisements, and measures what the simulator only predicted.

**Tech Stack:** Kotlin (extended advertising, sync), TypeScript (sync protocol), fast-check, adb harnesses.

**Spec:** [`../docs/design.md`](../docs/design.md) §6.3–§6.6

**Devices:** 2 required, **3 strongly preferred**. A two-device relay test is possible but time-shifted and weaker — see Task 5.

**Depends on:** Phase 3 complete, throughput measured.

## Global constraints

All from [`README.md`](README.md#global-constraints). Enforced here:

- **No performance number ships unmeasured** (defect 9).
- **`SPRAY_L` is configurable and chosen by measurement**, not asserted (defect 8).
- Validation order unchanged: cheap checks before signature verification.

## File structure

| File | Responsibility |
|---|---|
| `src/mesh/sync.ts` | The digest → request → transfer → ack conversation |
| `src/mesh/RealMeshEngine.ts` | Extended to drive sync on peer events |
| `modules/.../ble/ExtendedAdvertiser.kt` | BLE 5 extended advert carrying the bloom digest |
| `scripts/bench-routing.mjs` | L sweep in the simulator |
| `scripts/harness/relay.mjs` | Three-device relay measurement |
| `scripts/harness/two-device-relay.mjs` | Time-shifted fallback |

---

## Task 1: The sync protocol

Two peers meet with limited time and limited bandwidth. They must work out what to exchange without transferring everything.

**Files:**
- Create: `src/mesh/sync.ts`
- Test: `src/mesh/__tests__/sync.test.ts`

**Interfaces:**
- Produces: `planSync(theirDigest, myQueue): SyncPlan` where `SyncPlan = { offer: FrameId[]; request: FrameId[] }`, and `applySyncPlan(...)`.

- [ ] **Step 1: Write the failing tests**

```typescript
test('offers frames the peer probably lacks', () => { /* their bloom misses my frame */ });
test('does not offer frames the peer probably has', () => { /* their bloom hits */ });
test('offers everything to the destination regardless of their digest', () => { /* ... */ });
test('caps the offer at the bandwidth budget', () => {
  // With measured throughput and a typical 8-second encounter, only N frames fit.
});
test('prioritises frames addressed to this peer over relay traffic', () => { /* ... */ });
test('property: a sync plan never offers a frame with zero copies', () => { /* fast-check */ });
```

The bandwidth cap uses the throughput measured in Phase 3. This is the first place that number does real work — a sync plan that assumes infinite bandwidth stalls the whole mesh behind one oversized transfer.

- [ ] **Step 2: Implement.** Order the offer by: frames addressed to this peer first, then by fewest copies remaining, then by soonest expiry. Truncate to the budget.

- [ ] **Step 3: Commit**

---

## Task 2: Extended advertising with the bloom digest

**Files:**
- Create: `ble/ExtendedAdvertiser.kt`
- Test: `ble/ExtendedAdvertiserTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `extended payload carries the 32-byte bloom filter`() { /* ... */ }
@Test fun `falls back to the legacy beacon when extended is unsupported`() { /* ... */ }
@Test fun `extended payload stays within the reported maximum`() {
    // Use BluetoothAdapter.getLeMaximumAdvertisingDataLength(), never a hard-coded 255.
}
```

- [ ] **Step 2: Implement.** Query `isLeExtendedAdvertisingSupported()` and `getLeMaximumAdvertisingDataLength()` at start. If extended is unavailable — common on mid-range phones — fall back to the legacy beacon and exchange digests over GATT instead. Both paths must work; the fallback is not an error state.

- [ ] **Step 3: Verify on both phones.** Diagnostics already shows `Extended advertising: Supported / Not supported`. Confirm the app behaves correctly in both cases, forcing the fallback with a debug flag if both phones happen to support it.

- [ ] **Step 4: Commit**

---

## Task 3: Wire sync into the engine

**Files:**
- Modify: `src/mesh/RealMeshEngine.ts`
- Test: `src/mesh/__tests__/engine-sync.test.ts`

- [ ] **Step 1: Write the failing tests against the simulator first**

```typescript
test('two peers converge on the same frame set after syncing', () => { /* ... */ });
test('a frame with zero copies left is not relayed further', () => { /* ... */ });
test('an ack propagates back and marks the message delivered', () => { /* ... */ });
test('a peer that goes out of range mid-sync leaves no partial frame', () => { /* ... */ });
```

Simulator first, then hardware. A bug found here costs seconds; the same bug found on two phones costs an afternoon.

- [ ] **Step 2: Implement.** On a peer appearing: exchange digests, compute a plan, transfer, ack. On disconnect mid-transfer: discard the partial reassembly and leave the queue untouched.

- [ ] **Step 3: Run the full Phase 2 suite** — every simulator test must still pass unchanged.

- [ ] **Step 4: Commit**

---

## Task 4: Choose L by measurement

Closes defects 8 and 9.

**Files:**
- Create: `scripts/bench-routing.mjs`

- [ ] **Step 1: Write the sweep**

Simulate a festival-shaped crowd: 50 virtual devices, random-waypoint movement, 50 m range, 20% loss. Sweep **`L ∈ {2, 4, 8, 16, 32}`** over at least 20 seeded runs each. For each, report **median delivery delay**, delivery rate, and overhead ratio (total transmissions ÷ messages delivered).

`L = 1` is deliberately excluded from the candidate set. `sprayDecision(1, peer, dest)` returns `0` — the frame is held and only ever delivered directly, so a mesh with `L = 1` does not relay at all. Including it in a sweep whose selection rule prefers the smallest value invites the rule to disable the product.

```bash
npm run bench:routing
```

- [ ] **Step 2: Pick L on delay, not on delivery rate.** Choose the smallest `L` whose **median delay** is within 10% of the best observed.

Delivery rate is the wrong selection metric and Spyropoulos et al. show why: in their evaluation "the delivery ratio of almost all schemes … was above 90% for all traffic loads." With a 4-hour TTL the delivery curve saturates and cannot discriminate between candidates, so a rule keyed on it silently returns the smallest `L` on the list regardless of merit. §4.1 of the paper — *"Choosing L to Achieve a Required Expected Delay"* — selects against delay for exactly this reason. Delivery rate stays in the report as a saturation check.

Record the node count the sweep ran at. Lemma 4.3 makes `L_min` a function of the number of nodes `M`, so a value chosen at 50 nodes does not transfer unexamined to a crowd of thousands.

**Known simulator limitation, state it in the report:** the Phase 2 `Sim` models range, loss, latency and churn but has **no MAC contention model**. The paper implemented slotted random access with collision detection precisely because contention dominates at density — under it, epidemic delivery "plummeted to less than 50%". Expect the simulator to be optimistic at crowd density, and treat Task 6's hardware comparison as the check on that.

- [ ] **Step 3: Record the full table in the ledger** and set `SPRAY_L_DEFAULT` to the chosen value with a comment naming the run that chose it.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "perf: choose spray L from measurement

Closes defects 8 and 9. Table recorded in plans/README.md."
```

---

## Task 5: The three-device relay test — **THE GATE**

**Files:**
- Create: `scripts/harness/relay.mjs`, `scripts/harness/two-device-relay.mjs`

- [ ] **Step 1: With three phones**

Place A and B beyond BLE range of each other — roughly 60–80 m outdoors with a wall or a car between them; verify in Diagnostics that neither lists the other. Put C midway, in range of both.

Send from A to B.

**Expected:** B receives it. C's Diagnostics shows `frames relayed: 1`. C's chat with A and B shows nothing.

- [ ] **Step 2: With only two phones — the time-shifted fallback**

Weaker, but it does prove store-and-forward and relay separately:

1. A and C together, B switched off. A sends to B. C's queue shows the frame.
2. Move A out of range and switch it off. Switch B on next to C.
3. C relays to B.

Diagnostics on C must show the frame arriving with `hopCount: 0` and leaving with `hopCount: 1`.

- [ ] **Step 3: Record the result** — hop count, wall-clock delivery time, and which route was used.

- [ ] **Step 4: Commit**

---

## Task 6: Compare hardware against the simulator

The reason the simulator exists.

- [ ] **Step 1: Run the same scenario in both** — same device count, same distances, same message.
- [ ] **Step 2: Compare** delivery rate, hop count, and latency.
- [ ] **Step 3: If they disagree by more than roughly 20%, investigate before proceeding.** Either the simulator's model is wrong (fix the model — every later phase trusts it) or the real implementation has a bug the simulator would have caught. Both outcomes are valuable; neither should be waved through.
- [ ] **Step 4: Record both columns in the ledger side by side.**

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass
- [ ] `npm run bench:routing` → table produced, L chosen from it
- [ ] **A message travels A → C → B over real Bluetooth, and C cannot read it** ← the gate
- [ ] Simulator and hardware agree within 20% on delivery rate
- [ ] Ledger rows filled: delivery rate, overhead ratio at chosen L

## Re-check before moving on

- [ ] Re-run Phases 0–3 exit gates. All green.
- [ ] **Seam check:** `git diff --stat <phase-2-tag>..HEAD -- src/app/ ':!src/app/diagnostics.tsx'` → empty apart from Diagnostics rows.
- [ ] Kill the app on the relay mid-transfer. No frame is lost from the origin's queue and no duplicate is delivered.
- [ ] Send 50 messages in a burst. The queue stays bounded at `maxQueueFrames` and drops are counted by reason.
- [ ] Confirm `framesDropped` shows a plausible distribution — all zeros usually means validation is not running.

## Updating the index

- [ ] Tick defects 8 and 9 in the [defect tracker](README.md#defect-regression-tracker)
- [ ] Fill delivery rate and overhead ratio in the ledger
- [ ] Set Phase 4 to `☑ Done`

## What later phases rely on

| Produced here | Used by |
|---|---|
| Working real mesh | Phases 5–8 |
| Chosen `SPRAY_L` | Phase 5 battery profile |
| Sync protocol | Phase 6 location updates ride on it |
| Simulator/hardware agreement | Phase 8 attack simulation credibility |
