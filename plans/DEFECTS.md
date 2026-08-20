# Audit Findings — 2026-08-20

The plan set was audited by independent reviewers given only a file path and told to
refute what they read. No session context, no reasoning, no summary. Three of eleven
planned audits completed before the run was cut short; the remaining eight are listed
as outstanding.

Every finding below was **re-verified independently** before being acted on. Findings
the auditors got wrong are recorded as rejected, with the reason.

## Fixed

| # | Defect | Severity | Verified by | Status |
|---|---|---|---|---|
| A1 | Spec required every hop to verify `origin_sig` against a key relays cannot have — multi-hop relaying impossible by construction | **Critical, architectural** | Read spec §6.3 + `phase-2:424` + `phase-8:296` | Fixed: `origin_pubkey` in frame; integrity at every hop, authenticity at destination |
| A2 | Pickle key discarded (`let _ = key;`); private keys written to disk in plaintext; exit gate unreachable | **Critical, security** | Read `phase-0` source | Fixed: `AccountPickle::encrypt` / `from_encrypted` |
| A3 | `cargo run --bin uniffi-bindgen` — no such bin target | Blocking | UniFFI 0.28 manual | Fixed: `[[bin]]` + `uniffi-bindgen.rs` + `--features=uniffi/cli` |
| A4 | UDL `sequence<u8>` maps to `List<UByte>`, not `ByteArray` | Blocking | UniFFI Kotlin backend | Fixed: `bytes` in UDL |
| A5 | `[Name=create]` makes an alternate constructor; `MoshgridAccount()` would not compile | Blocking | UniFFI `object.rs` | Fixed: constructor named `new` |
| A6 | UniFFI is MPL-2.0 — fails the project's own licence gate | Blocking | crates.io API: `"MPL-2.0"` | Fixed: allow-listed with rationale; recorded as accepted risk |
| A7 | `sign` absent from the UDL — the signed-bundle scheme could not work | Blocking | grep of UDL block | Fixed: declared on the interface |
| A8 | Four files named Vitest; project uses Jest | Minor | grep | Fixed |
| A9 | `cargo deny` resolves `deny.toml` from cwd | Minor | — | Fixed: `--config` passed |

## Outstanding — not yet fixed

| # | Defect | Severity | Notes |
|---|---|---|---|
| B1 | `expo-sqlite` cannot run under jest — Phase 1 Tasks 1, 5, 6 test suites unexecutable | **Critical** | Fix is an injected DB opener so tests run against `node:sqlite`. Not yet applied |
| B2 | `expo-crypto` returns undefined under jest — `digestStringAsync`, `randomUUID` | **Critical** | Fix is a pure-JS hash and an injectable id function |
| B3 | `deviceIdFromEd25519` declared sync, implemented async; one test passes **vacuously** | High | `expect(a).not.toBe(b)` on two Promises always passes |
| B4 | `pickleKey()` imported in Phases 1 and 2, defined nowhere | High | Needs a Keystore bridge task |
| B5 | `createAccount`/`unpickleAccount` do not match Phase 0's produced API | High | Commented, not yet reconciled |
| B6 | `describeMeshEngineContract` exported from a `.test.ts` — jest fails the suite | High | Move to `src/mesh/testing/` |
| B7 | `getEngine` fake branch seeds a Sim with only self — the Phase 2 phone gate is unreachable | High | Also imports `BleLink` before Phase 3 creates it, breaking `tsc --noEmit` |
| B8 | `ratchet_survives_out_of_order_delivery` does not test out-of-order delivery | High | Inbound session decrypts `first`, so order is preserved |
| B9 | `a_tampered_ciphertext_fails_to_decrypt` passes for the wrong reason | Medium | Appending a char breaks base64 length before any MAC check |
| B10 | Bloom filter is dead code in Phase 2 — nothing calls `mightContain` | Medium | `framesFor(_peer)` ignores its argument |
| B11 | `forgeFrame` spread overwrites padded payload with unpadded | Medium | `{ ...base, ...overrides }` defeats size bucketing |
| B12 | 43 placeholder test stubs across phases 3–8 | High | Violates the plan set's own no-placeholders standard |
| B13 | Keystore `key.encoded` returns null for hardware-backed keys | High | Wrap/unwrap is the only working design |
| B14 | `myBundle()` mints a fresh one-time key per call; old ones silently evicted | Medium | Sessions fail with no diagnostic |

## Rejected — auditor was wrong

| Claim | Why rejected |
|---|---|
| `buildFrame` called with an invalid `copies` key | `overrides.copies` is the *source* for `sprayL`, which is in the signature. Type-checks. Auditor found and corrected this itself |
| Bloom filter math or bounds wrong | Measured empirically: 4.32% vs 4.37% theoretical at n=30; chi² confirms hash independence. Sound |
| `FrameQueue` eviction test expectations wrong | Traced by hand; produces `['b','c']` as asserted |
| Rust session tests will not compile for want of `mut` | UniFFI interface methods generate `&self` with interior mutability, matching the existing `Mutex` pattern |
| `randomUUID` not exported by expo-crypto | It is exported and synchronous. The runtime problem is real (B2); the API claim was not |

## Not yet audited

`phase-3` · `phase-4` · `phase-5` · `phase-6` · `phase-7` · `phase-8` · `phase-9` · `docs/design.md`

One defect in `phase-6` was found without an auditor: the geo reference test comments
"London Eye to Big Ben ≈ 700 m" while the true haversine distance between the given
coordinates is **451 m**, and `toBeCloseTo(470, -2)` is a ±50 m tolerance — loose
enough to be nearly meaningless. Not yet fixed.
