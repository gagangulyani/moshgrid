# MoshGrid V1 — Implementation Index

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement these plans task-by-task. All steps use `- [ ]` checkbox syntax for tracking.

**Spec:** [`../docs/design.md`](../docs/design.md) — the plans argue from the spec. Read both.

**Goal:** an Android app that lets people find and message their friends at a festival when the cell network is dead, relaying phone-to-phone over BLE.

---

## How to use this index

1. Phases are **sequential**. Each depends on the one before it. Do not start a phase whose predecessor's exit gate is not green.
2. Tick a phase's box here only when **every** exit gate in its file passes. The gates are commands with expected output, not judgement calls.
3. `Verify` on each phase re-runs earlier phases' gates. Regressions surface at the next phase boundary rather than three phases later.
4. If a gate fails, stop. Do not proceed and fix later — the whole point of the gates is that later work is worthless if an earlier layer is wrong.

> **Audit status:** all 11 files were independently audited on 2026-08-20, each auditor
> given only a file path and told to refute it. **Every audit found blocking problems;
> none returned SOUND.** 43 defects fixed, 24 open, 9 auditor claims rejected after
> independent verification.
>
> **Read [`DEFECTS.md`](DEFECTS.md) before executing any phase.** Two things in it
> matter more than the individual entries: four defects were introduced *while fixing
> other defects*, and a large class of the tests as written **cannot fail**.
>
> Phase 7 was rewritten outright — its original architecture rested on an API that does
> not exist. Phase 1's database and hashing were rebuilt around injected drivers, because
> `expo-sqlite` and `expo-crypto` have no implementation under jest and the phase
> therefore had no executable test suite. Phase 8's defect-3 regression test was rewritten
> after it was found to pass against a build with signature verification removed.
>
> What is left is mostly unwritten test bodies (B12, B31) and known gaps, chief among them
> **B23: replay protection is specified but not implemented.**

## Progress

| # | Phase | Status | Devices | Visible at the end |
|---|---|---|---|---|
| 0 | [Foundations & gates](phase-0-foundations.md) | ☐ Not started | 1 | Diagnostics screen: module loaded, crypto self-test green |
| 1 | [Identity & contacts](phase-1-identity.md) | ☐ Not started | 1 | Scan a QR, contact appears and persists |
| 2 | [Chat over simulated mesh](phase-2-simulated-mesh.md) | ☐ Not started | 1 | Full multi-hop chat working on one device |
| 3 | [Real BLE link layer](phase-3-ble-link.md) | ☐ Not started | 2 | Two phones discover each other, live RSSI |
| 4 | [Real mesh messaging](phase-4-real-mesh.md) | ☐ Not started | 2–3 | A → C → B relay between real phones |
| 5 | [Background & battery](phase-5-background.md) | ☐ Not started | 2 | App closed, messages still arrive; measured drain |
| 6 | [Location & radar](phase-6-location-radar.md) | ☐ Not started | 2 | Friend's live bearing and distance |
| 7 | [Offline map](phase-7-offline-map.md) | ☐ Not started | 2 | MBTiles basemap with live pins in aeroplane mode |
| 8 | [Hardening](phase-8-hardening.md) | ☐ Not started | 2 | Attack suite and fuzzers green |
| 9 | [iOS](phase-9-ios.md) | ☐ Blocked | — | Deferred until an iOS device exists |

Status values: `☐ Not started` · `◐ In progress` · `☑ Done` · `☐ Blocked`

## Dependency graph

```
Phase 0  Foundations
   │     Expo dev client · Kotlin module · vodozemac AAR · CI · licence gate
   │
   ├──> Phase 1  Identity & contacts          (needs: crypto binding)
   │        │
   │        └──> Phase 2  Simulated mesh      (needs: sessions, storage)
   │                 │
   │                 │    ← UI is COMPLETE here. Everything below swaps
   │                 │      the engine underneath an unchanged UI.
   │                 │
   │                 └──> Phase 3  BLE link   (needs: MeshEngine interface)
   │                          │
   │                          └──> Phase 4  Real mesh    (needs: link, routing)
   │                                   │
   │                                   ├──> Phase 5  Background
   │                                   │
   │                                   ├──> Phase 6  Location & radar
   │                                   │        │
   │                                   │        └──> Phase 7  Offline map
   │                                   │
   │                                   └──> Phase 8  Hardening
   │
   └──> Phase 9  iOS  (blocked on hardware; needs Phases 3–5 complete)
```

**The critical seam is between Phase 2 and Phase 3.** At the end of Phase 2 the app is feature-complete for chat and runs entirely on `FakeMeshEngine`. Phases 3 onward replace the engine underneath a UI that does not change. If a Phase 3+ change requires touching UI code, the interface was wrong — stop and fix the interface.

## Global constraints

Copied verbatim from the spec. Every task's requirements implicitly include these.

- **Licensing:** all runtime dependencies MIT, Apache-2.0, BSD, or ISC. **No GPL or AGPL** in the dependency tree. CI-enforced.
- **Crypto:** vodozemac (Apache-2.0). Never `libsignal` (AGPL-3.0-only).
- **X25519 shared secret is 32 bytes.** Asserted in code.
- **Contributory-DH check applied ourselves** — reject all-zero DH outputs; do not wait for upstream.
- **Signed routing headers.** Every frame's immutable routing fields are covered by the origin's Ed25519 signature.
- **Validation order is fixed:** cheap checks before signature verification, so unsigned garbage cannot force crypto work on every peer.
- **`MAX_HOPS` = 8. `SPRAY_L` default 8, configurable. `MAX_FRAME_BYTES` = 4096.**
- **Size buckets:** payloads padded to 64 / 256 / 1024 / 4096 bytes.
- **Legacy BLE advert carries 6 usable bytes.** Presence beacon only.
- **`ADVERTISE_MODE_BALANCED` is 250 ms.** Named constants only, never magic numbers.
- **TDD without exception.** Test written first, observed failing for the stated reason, then made to pass.
- **No performance number ships unmeasured.** Cite the harness command that produced it or do not state it.
- **Every defect fix gets a regression test named for the defect.**

## Defect regression tracker

Ten defects were verified in the source PDFs on 2026-08-20. Each must end with a named passing test. Tick when the test exists and passes.

| # | Defect | Named test | Phase | Done |
|---|---|---|---|---|
| 1 | X25519 shared secret claimed 128 bytes; is 32 | `test_x25519_shared_secret_is_32_bytes` | 1 | ☐ |
| 2 | libsignal AGPL priced as "Free" | `flags AGPL packages` + `cargo deny check licenses` | 0 | ☐ |
| 3 | Bridgefy blamed on custom crypto; broke again on libsignal | `test_forged_routing_header_is_rejected` | 8 | ☐ |
| 4 | Bloom hashes read the same low 16 bits | `test_bloom_hashes_are_independent` | 2 | ☐ |
| 5 | 31-byte advert payload is impossible | `test_legacy_advert_fits_in_six_bytes` | 3 | ☐ |
| 6 | Advertising interval 211 ms is not a real preset | `test_advertise_interval_uses_named_constant` | 3 | ☐ |
| 7 | Expo wrongly rejected for background BLE | Phase 0 exit gate (module runs on device) | 0 | ☐ |
| 8 | Spray L=32 asserted as standard | `test_spray_l_is_configurable` | 4 | ☐ |
| 9 | Delivery rates cited without measurement | `bench:routing` report committed | 4 | ☐ |
| 10 | Purple Guide misattributed | n/a — text removed from scope | — | ☑ |

## Measured-numbers ledger

Every performance number the project states must appear here with the command that produced it. Empty until measured. **Never fill a row from an estimate.**

| Metric | Value | Command | Date |
|---|---|---|---|
| Bloom FP rate @ 30 frames | — | `pnpm bench:bloom` | — |
| Delivery rate, 20-node sim | — | `pnpm bench:routing` | — |
| Overhead ratio at chosen L | — | `pnpm bench:routing` | — |
| BLE GATT throughput, bytes/sec | — | `pnpm harness:throughput` | — |
| Battery drain, passive, %/hour | — | `pnpm harness:battery` | — |
| Time to first peer discovery | — | `pnpm harness:discovery` | — |
