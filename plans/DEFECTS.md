# Audit Findings — 2026-08-20

Every plan file and the spec were audited by independent reviewers, each given only a
file path and told to refute what they read. No session context, no reasoning, no
summary — an auditor handed a justification tends to agree with it.

Every finding below was **re-verified independently** before being acted on. Findings
the auditors got wrong are recorded as rejected, with the reason.

**Status: 9 of 11 audited.** Phases 8 and 9 in progress.
**Every completed audit found blocking problems.** None returned SOUND.

---

## The three defect classes

Sorting the findings by cause turns out to matter more than sorting by severity.

**1. Claims that were never checked.** APIs that do not exist (`LocalTilesSource`),
constants asserted from memory, licences assumed rather than read, test runners named
that the project does not use. The largest class, and the same failure mode this
project was created to correct in the source PDFs.

**2. Defects introduced *while fixing* other defects.** Four of them, all mine, all in
the same afternoon. Fixing the relay-verification hole broke the wire format, broke
`buildFrame`, inverted a Phase 8 test into defending the original bug, and shipped a
privacy regression. **A correction is a change, and changes need auditing too.**

**3. Tests that cannot fail.** 43+ stubs with empty bodies, assertions with tolerances
so wide they accept anything (`toBeCloseTo(470, -2)` = ±50 m), `expect(a).not.toBe(b)`
on two Promises, and a Y-flip test that is three comments and no assertion. A green
suite that proves nothing is worse than no suite, because it is trusted.

---

## Fixed

| # | Defect | Severity | Class | Verified by |
|---|---|---|---|---|
| A1 | Spec required every hop to verify `origin_sig` against a key relays cannot have — multi-hop relaying impossible by construction | **Critical, architectural** | 1 | spec §6.3 + `phase-2:424` + `phase-8:296` |
| A2 | Pickle key discarded (`let _ = key;`); private keys written to disk in plaintext; exit gate unreachable | **Critical, security** | 1 | read the source |
| A3 | `encodeFrame`/`decodeFrame` dropped `originPubkey` — the key never reached the wire; every relayed frame would die at `id_key_mismatch` | **Critical** | **2** | field count 12 vs type's 13 |
| A4 | Phase 8 asserted "unknown origin is dropped" — backwards under the corrected model; the test defended the bug it was meant to catch | **Critical** | **2** | read after spec change |
| A5 | `buildFrame` could not construct a valid frame after the type gained a field | High | **2** | signature vs type |
| A6 | **Privacy regression:** `origin_pubkey` in cleartext makes presence confirmable with cryptographic certainty by anyone holding a public contact bundle | **High** | **2** | spec §6.1 vs §6.3 |
| A7 | Guarantee "nobody can alter a frame in flight" was false — `hop_count`/`copies` are unsigned and attacker-controlled | High | 1 | `phase-8:401` pins it as intended |
| A10 | `copies > previous hop's value` is vacuous — no signed hop chain exists, so the check compares the field to itself | Medium | 1 | — |
| A11 | `sessions` keyed per contact breaks Olm, which has concurrent sessions per device; overwriting loses in-flight messages | High | 1 | schema vs Olm semantics |
| A12 | **Phase 7 architecture was wrong twice:** PMTiles rejected via an invented causal clause welded to a real quote; `LocalTilesSource` does not exist and custom tile sources cannot be registered from Kotlin at all | **Critical** | 1 | MapLibre docs + source class list |
| A13 | Planetiler caps `maxzoom` at 16; the specified z12–17 build throws at startup | Blocking | 1 | `PlanetilerConfig.MAX_MAXZOOM` |
| A14 | 50 MB pack tripwire implied 80 KB/tile — could never fire | Medium | 1 | computed tile counts |
| A15 | UniFFI is MPL-2.0 — failed the project's own licence gate | Blocking | 1 | crates.io API |
| A16 | `cargo run --bin uniffi-bindgen` with no such bin target | Blocking | 1 | UniFFI manual |
| A17 | UDL `sequence<u8>` maps to `List<UByte>`, not `ByteArray` | Blocking | 1 | UniFFI Kotlin backend |
| A18 | `[Name=create]` makes an alternate constructor; `MoshgridAccount()` would not compile | Blocking | 1 | UniFFI `object.rs` |
| A19 | `sign` absent from the UDL — the signed-bundle scheme could not work | Blocking | 1 | grep of UDL block |
| A20 | Doze **ignores wake locks**; the Phase 5 wake window was built on one. Android 12+ also blocks background FGS start | **Critical** | 1 | Android docs |
| A21 | GATT servers cannot request an MTU — `requestMtu()` is client-only | Blocking | 1 | Android API |
| A22 | `0xFFFF` manufacturer ID forbidden by Bluetooth SIG in shipping products, not merely "for testing" | Blocking | 1 | SIG guidance |
| A23 | Seam check and Phase 3 exit gates mutually unpassable — gates require editing Diagnostics, constraint forbids touching `src/app/` | Blocking | 1 | read both |
| A24 | Spray-and-wait `L` selected on delivery rate, which saturates; must select on delay. `L=1` in the sweep disables relaying entirely | High | 1 | Spyropoulos §4.1 |
| A25 | Reference distance test: comment said 700 m, assertion said 470, truth is 450.95, tolerance ±50 m — passed while being wrong three ways | Medium | 3 | computed |
| A26 | False justification for haversine — equirectangular with `cos(lat)` agrees to under a millimetre here | Low | 1 | computed |
| A27 | `shareLocation` declared "on `MeshEngine`" but absent from the interface | High | 1 | read the interface |
| A28 | Background location cannot be requested "while the app is closed" — prompts need a foreground activity | Medium | 1 | — |
| A29 | Raw magnetometer gives magnetic heading in the device frame; `Location.watchHeadingAsync` `trueHeading` is declination-corrected | Medium | 1 | Expo docs |
| A30 | Six of nine defect-table rows pointed at the wrong task | Medium | 1 | task headers |
| A31 | Named regression test `test_no_copyleft_licences_in_tree` existed nowhere | Low | 1 | grep |
| A32 | Four files named Vitest; the project uses Jest | Low | 1 | grep |

## Outstanding — recorded, not yet fixed

| # | Defect | Severity |
|---|---|---|
| B23 | **Replay protection still does not exist.** The spec was rewritten to specify a highest-seen + 1024-bit bitmap and named `highest_sequence` / `replay_bitmap` columns — but no schema declares them, `highestSequence` is still read twice and written zero times, and no test covers the bitmap. **This was marked "Fixed" on the strength of a prose change alone; that was a false green and is corrected here.** | **High** |
| B24 | No test asserts that the seen-set is written only *after* signature verification — the keyless-flood eviction attack the spec describes is untested in the phase whose job is to attack | High |
| B1 | `expo-sqlite` cannot run under jest — Phase 1 Tasks 1, 5, 6 test suites unexecutable. Fix is an injected DB opener | **Critical** |
| B2 | `expo-crypto` returns undefined under jest — `digestStringAsync`, `randomUUID`. Fix is a pure-JS hash and the `idFn` seam | **Critical** |
| B3 | `deviceIdFromEd25519` declared sync, implemented async; `expect(a).not.toBe(b)` on two Promises passes **vacuously** | High |
| B4 | `pickleKey()` imported in Phases 1 and 2, defined nowhere | High |
| B5 | `createAccount`/`unpickleAccount` do not match Phase 0's produced API | High |
| B6 | `describeMeshEngineContract` exported from a `.test.ts` — jest fails the suite for containing no tests | High |
| B7 | `getEngine` fake branch seeds a Sim with only self, so the Phase 2 phone gate is unreachable; also imports `BleLink` before Phase 3 creates it, breaking `tsc --noEmit` | High |
| B8 | `ratchet_survives_out_of_order_delivery` does not test out-of-order delivery — the inbound session decrypts `first`, preserving order | High |
| B9 | `a_tampered_ciphertext_fails_to_decrypt` passes for the wrong reason — appending a char breaks base64 length before any MAC check | Medium |
| B10 | Bloom filter is dead code in Phase 2 — nothing calls `mightContain`; `framesFor(_peer)` ignores its argument | Medium |
| B11 | `forgeFrame` spread overwrote the padded payload with the unpadded original, defeating size bucketing | Medium |
| B12 | 43+ placeholder test stubs across phases 3–8 | High |
| B13 | Keystore `key.encoded` returns null for hardware-backed keys; wrap/unwrap is the only working design | High |
| B14 | `myBundle()` mints a fresh one-time key per call; old ones silently evicted, so a friend who scanned yesterday may hold a dead pre-key | Medium |
| B15 | Spec says SipHash-2-4; plan implements seeded FNV-1a. Digest is public, so an attacker can grind ids to force collisions | Medium |
| B16 | `MAX_FRAME_BYTES = 4096` cannot hold a 4096-byte payload plus ~150 bytes of header and signature | Medium |
| B17 | `planSync` lacks a `peerId` argument, so "frames addressed to this peer" is uncomputable; `QueuedFrame` has no destination field | High |
| B18 | `SyncPlan.request` is uncomputable from a bloom digest — a membership oracle is not enumerable | High |
| B19 | Phase 4's 20% simulator/hardware agreement threshold is invented and sits in an exit gate | Medium |
| B20 | Phase 2 `Sim` has no MAC contention model; the paper found contention drops epidemic delivery below 50% | Medium |
| B21 | DeviceId is a 64-bit truncation used as a contacts primary key — collision grinding unaddressed | Low |
| B22 | Legacy advertising also has a scan response with its own 31-byte budget, unmentioned — "presence beacon only" is stronger than the arithmetic forces | Low |

## Rejected — auditor was wrong, verified before rejecting

| Claim | Why rejected |
|---|---|
| `buildFrame` called with an invalid `copies` key | `overrides.copies` is the *source* for `sprayL`, which is in the signature. The auditor found and corrected this itself |
| Bloom filter math or bounds wrong | Measured empirically: 4.32% vs 4.37% theoretical at n=30; chi² confirms independence. All four published rates recomputed and correct |
| `FrameQueue` eviction test expectations wrong | Traced by hand; produces `['b','c']` as asserted |
| Rust session tests will not compile for want of `mut` | UniFFI interface methods generate `&self` with interior mutability, matching the existing `Mutex` pattern |
| `randomUUID` not exported by expo-crypto | It is exported and synchronous. The *runtime* problem is real (B2); the API claim was not |
| `ADVERTISE_MODE_BALANCED` might be ~211 ms | 400 units × 0.625 ms = exactly 250 ms. 211 ms would be 337.6 units, not a constant. Defect 6 stands |
| `relativeBearing` expectations wrong | Both correct and mutually consistent under the standard signed normalisation |
| Magnetic declination breaks the UK radar | ~4 m error at 200 m, below GPS noise. Real issue, but only abroad — 46 m at 13°E. My own framing was wrong, not the plan's arithmetic |
| The BLE 31-byte budget is wrong | Every number verified twice, independently. 31 − 3 − 18 − 4 = 6 |

## Auditors who corrected *me*

Worth recording separately, because it is evidence the cold-audit setup worked rather
than producing agreeable noise:

- Phase 1's auditor rejected my brief's premise that `signableBytes` calls
  `deviceIdFromEd25519`. It does not; the `Consumes:` line was stale.
- Phase 6's auditor corrected my stub count (14, not 11) and my framing of magnetic
  declination as a UK problem.
- Phase 7's auditor corrected the stub count again (5, not 4) and found that the one
  test I singled out as load-bearing contains no assertion at all.
- Phase 4's auditor noted that a two-device time-shifted relay test proves *more* than
  I credited it with — but that both its hop-count readings come from the device under
  test, which is not evidence.
