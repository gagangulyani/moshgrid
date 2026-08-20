# MoshGrid V1 — Design Specification

**Status:** approved for planning
**Date:** 2026-08-20
**Scope:** V1 only. Later scope is listed under Non-Goals with the phase that revisits it.

---

## 1. What we are building

An Android app that lets people find and message their friends at a festival **when the cell network is dead**, by relaying messages phone-to-phone over Bluetooth Low Energy.

V1 delivers three things, on two or more physical Android phones, with no server anywhere:

1. **Identity and contacts** — generate a keypair on device, add a friend by scanning their QR code.
2. **Encrypted 1:1 chat** — text messages, end-to-end encrypted, delivered over the BLE mesh with store-and-forward when the recipient is out of range.
3. **Find your friend** — who is in range right now, and which direction and how far away they are, on a radar view and on an offline map.

## 2. Non-goals for V1

| Deferred | Why | Revisited in |
|---|---|---|
| iOS | No test device available; iOS 26 restricts BLE relaunch to AccessorySetupKit apps, so iOS can never be a reliable relay | Phase 9 |
| Backend server, accounts, push | Competes with mesh work; every V1 feature works without it | Post-V1 |
| Medical dispatch SoS | Safety-critical and regulated; needs a signed medical provider and a proven mesh first | Own spec cycle |
| Super-node relay hardware | Independent physical product | Own spec cycle |
| Festival schedule, weather | Independent of the mesh; proves nothing about it | Post-V1 |
| Group chat | 1:1 first. Group ratchets are a separate design | Post-V1 |

## 3. Decisions and rationale

### 3.1 Dependency licensing policy

**All runtime dependencies must be permissively licensed (MIT, Apache-2.0, BSD, ISC). No GPL or AGPL in the dependency tree.** Enforced in CI (Phase 0, Task 5).

This exists because `libsignal` — the crypto library the source PDF specified and priced at "Free" — is **AGPL-3.0-only**. Building a closed or permissively-licensed product on it would require either open-sourcing under AGPL or negotiating a commercial licence from Signal Messenger LLC.

The decision is one-way in one direction only. Permissive dependencies leave open-sourcing, closed-sourcing, and selling all available later. AGPL dependencies force a crypto-core replacement — the highest-risk refactor in the codebase — if the licence posture ever changes.

### 3.2 Cryptography: vodozemac

**vodozemac** (Apache-2.0), Matrix's Rust implementation of Olm and Megolm, consumed on Android through the UniFFI-generated Kotlin AAR.

Olm is 3DH + Double Ratchet — the same construction as Signal Protocol, and the right shape for this problem: sessions establish asynchronously from a published one-time key, and the ratchet tolerates out-of-order and long-delayed delivery. Both properties are mandatory when the recipient may be offline for hours.

MLS (OpenMLS, mls-rs) was considered and rejected: MLS assumes a Delivery Service that orders handshake messages. A delay-tolerant mesh provides no ordering guarantee, so MLS would fight the transport.

**Known issue, handled:** in February 2026 a researcher reported that vodozemac does not reject all-zero X25519 Diffie-Hellman outputs (non-contributory keys). Matrix disputes exploitability — identity keys and pre-keys are Ed25519-signed and verified before session establishment — and has agreed to add the check as defence in depth. **We apply the check ourselves rather than waiting for the upstream release** (Phase 1, Task 4).

### 3.3 The security lesson that shapes the architecture

Bridgefy was broken in 2020 with custom cryptography. It adopted libsignal. It was **broken again in 2022** — the paper is titled *"Breaking Bridgefy, again: Adopting libsignal is not enough."*

The conclusion is not "pick a better crypto library." It is that **mesh-layer framing and metadata are a distinct attack surface below the crypto layer**, and encrypting the payload does nothing for it. So V1 treats the mesh layer as adversarial by design:

- Every frame's **routing header is signed by the origin**, not just its payload, and the origin's public key travels inside the signed region so any hop can check it without prior knowledge. Altering a sender, destination, or timestamp in transit is detectable everywhere.
- **Integrity is checked at every hop; authenticity only at the destination.** A relay can prove a frame is untampered without knowing who sent it — which is the only thing it can honestly know about a stranger. See §6.3.
- Mutable fields (`hop_count`, `copies`) are excluded from the signature but **bounds-checked** at every hop; a frame claiming impossible values is dropped.
- Frame IDs are random and deduplicated, so replay and amplification are bounded.
- Frames are size-bucketed so ciphertext length leaks less about message length.
- The attack suite in Phase 8 tests these properties directly, as tests that must fail before they pass.

What this does **not** claim is a mesh where only known contacts can transmit. Any device can join and emit well-formed frames; that is inherent to an open mesh and is bounded rather than eliminated (§10). The guarantee is narrower and worth stating precisely: **nobody can forge a frame attributed to a specific person, alter one in flight, or read one they are relaying.**

### 3.4 Platform: Android first

Android is the only platform where this can work correctly. A `ForegroundService` plus `PendingIntent`-based scanning runs inside the Bluetooth stack and survives Doze. iOS 26 relaunches an app for BLE events **only** if it uses AccessorySetupKit, which requires a system picker UI per peer and is unusable for automated mesh. The source PDF's own honest assessment agrees: iOS is an opportunistic contributor, never a relay.

This also matches the available hardware: two or more Android phones, no iPhone.

### 3.5 Framework: Expo with a dev client

React Native UI in TypeScript; the mesh engine as a **Kotlin Expo Module**.

The source PDF rejected Expo on the grounds that "background BLE requires native module modifications Expo does not permit." That is wrong. The Expo Modules API is written for exactly this, dev clients and prebuild allow arbitrary native code, and there are working ForegroundService + WakeLock BLE modules built this way.

Choosing Expo also buys EAS Build, so Gradle and signing are not a per-phase tax.

### 3.6 Offline maps: MBTiles, not PMTiles

MapLibre Native on Android supports the `pmtiles://` protocol, but **PMTiles sources do not support offline packs or caching** — they need live HTTP range requests, failing exactly when the app must work.

V1 uses **MBTiles festival packs** generated from OpenStreetMap extracts with Planetiler, registered through a custom `LocalTilesSource`. One file, guaranteed offline, no tile provider, no API key, no rate limit, no third-party terms that can change. Registering the source is incremental work given the Kotlin module already exists.

`OfflineManager.createPack()` was considered and rejected: it downloads from a live style URL, requires network at pack-creation time, and makes a tile vendor's terms part of the product.

## 4. Corrections carried from the source PDF

The source PDFs were AI-generated and audited on 2026-08-20. Ten defects were verified. Each is resolved here and enforced by a named test.

| # | PDF claim | Reality | Resolution | Enforced by |
|---|---|---|---|---|
| 1 | X25519 shared secret is 128 bytes | 32 bytes (RFC 7748) | Sizes asserted in code | Phase 1, Task 4 |
| 2 | libsignal, cost "Free" | AGPL-3.0-only | vodozemac (Apache-2.0) | Phase 0, Task 5 (CI licence gate) |
| 3 | Bridgefy failed due to custom crypto | Broken again *after* adopting libsignal | Signed routing headers | Phase 8, Task 1 |
| 4 | Bloom `h1 = d mod 256`, `h2 = (d>>8) mod 256` | Both read the low 16 bits — correlated, real FP rate exceeds the table | Two independently keyed SipHash instances | Phase 4, Task 2 |
| 5 | 31-byte advertisement payload | 31 bytes is the **total** AD budget including per-element headers; ~6 bytes are usable | Legacy advert is a presence beacon only | Phase 3, Task 2 |
| 6 | Advertising at 211 ms | `ADVERTISE_MODE_BALANCED` is 250 ms | Use named constants, never magic numbers | Phase 3, Task 1 |
| 7 | Expo cannot do background BLE | Expo Modules API is built for it | Expo + dev client | Phase 0, Task 2 |
| 8 | Spray copies L=32, "standard is 16" | *L* is a free parameter in the literature, not a constant | L is configurable, default 8, measured | Phase 4, Task 5 |
| 9 | Delivery rates 92–94%, overhead 3.2× | Uncited and simulation-dependent | No performance number ships unmeasured | Phase 4, Task 6 |
| 10 | Purple Guide is "the Event Safety Alliance's"; §4.3 | Published by the **Events Industry Forum**; no §4.3 found | Out of V1 scope; do not reuse this text | n/a |

**The BLE advertisement budget, computed:**

```
Flags element                1 (len) + 1 (type) + 1 (data)   =  3 bytes
128-bit Service UUID         1 (len) + 1 (type) + 16 (uuid)  = 18 bytes
                                                       used  = 21 bytes
                                                  remaining  = 10 bytes
Manufacturer Data header     1 (len) + 1 (type) + 2 (co. ID) =  4 bytes
                                        ACTUAL USABLE PAYLOAD =  6 bytes
```

The PDF budgets 15 bytes of fields after the UUID. No arrangement fits. V1 puts a 4-byte device-ID prefix and 2 flag bytes in the legacy advert and moves everything else to GATT and to BLE 5 extended advertising, detected at runtime via `BluetoothAdapter.isLeExtendedAdvertisingSupported()` rather than assumed.

## 5. Architecture

```
┌───────────────────────────────────────────────────────────────┐
│  UI — React Native / TypeScript                                │
│  Contacts · Chat · Nearby · Radar · Map · Diagnostics          │
└──────────────────────────────┬─────────────────────────────────┘
                               │  MeshEngine  (the swap seam)
              ┌────────────────┴─────────────────┐
              │                                  │
      FakeMeshEngine                       RealMeshEngine
      TypeScript, in-memory                       │
      virtual devices, range,          ┌──────────┴───────────┐
      loss, latency, churn             │ Session   vodozemac  │
      runs in Jest and on              │ Envelope  framing    │
      any machine, no radio            │ Routing   spray/wait │
                                       │ Link      BLE/Kotlin │
                                       └──────────────────────┘
```

**Every layer boundary is an interface with a fake behind it.** This is what makes "UI first, engine alongside" true rather than aspirational: the UI is built and tested against `FakeMeshEngine` from Phase 1, and routing correctness is proven in the simulator in milliseconds long before two phones are involved. When phones and simulator disagree, that disagreement is the bug — which is a far better debugging position than a mesh that is simply "not working."

### 5.1 Layer responsibilities

| Layer | Owns | Never sees |
|---|---|---|
| UI | Rendering, user intent | Keys, frames, radios |
| MeshEngine | The public contract both implementations honour | — |
| Session | vodozemac accounts, sessions, ratchets | Routing, radios |
| Envelope | Framing, signing, replay window, TTL, dedup, padding | Plaintext, radios |
| Routing | Spray-and-wait, queue, digests, peer selection | Plaintext, key material |
| Link | BLE advertising, scanning, GATT, connections | Plaintext, key material |

The Session layer sits **above** Envelope and Routing, so every relayed frame carries ciphertext only. A relaying phone cannot read what it forwards. This is structural, not a setting.

### 5.2 The MeshEngine interface

```typescript
export interface MeshEngine {
  start(config: EngineConfig): Promise<void>;
  stop(): Promise<void>;
  getIdentity(): Promise<Identity>;
  addContact(bundle: ContactBundle): Promise<Contact>;
  sendMessage(toDeviceId: DeviceId, body: string): Promise<MessageId>;
  shareLocation(toDeviceId: DeviceId, fix: LocationFix): Promise<void>;
  observePeers(): Observable<PeerSnapshot[]>;
  observeMessages(): Observable<MessageEvent>;
  observeDiagnostics(): Observable<DiagnosticsSnapshot>;
}
```

`FakeMeshEngine` and `RealMeshEngine` both implement it exactly. The UI imports the interface, never a concrete class. Which one is constructed is a single runtime decision in one file, driven by a config flag, so the app can run against the simulator on any machine.

## 6. Protocol

### 6.1 Identity

A vodozemac `Account` provides an Ed25519 signing key (long-term identity), a Curve25519 identity key, and rotating one-time keys.

`DeviceId` is the **first 8 bytes of SHA-256 over the Ed25519 public key**. Truncated for the wire; the full key is always used for verification.

### 6.2 Contact bundle (QR payload)

CBOR, then base45 for QR density:

```
ContactBundle {
  version:        u8          // 1
  ed25519_pub:    [u8; 32]    // long-term identity
  curve25519_pub: [u8; 32]    // identity key
  one_time_key:   [u8; 32]    // pre-key for async session setup
  display_name:   string      // max 32 bytes UTF-8
  signature:      [u8; 64]    // Ed25519 over all preceding fields
}
```

The signature is verified before the contact is stored. A bundle failing verification is rejected with a visible error, never stored.

### 6.3 Mesh frame

```
MeshFrame {
  // — signed, immutable —
  version:        u8
  frame_type:     u8       // 1=data 2=ack 3=digest_req 4=digest_resp
  frame_id:       [u8;16]  // CSPRNG, dedup key
  origin:         [u8;8]   // DeviceId of sender
  origin_pubkey:  [u8;32]  // Ed25519 public key of sender — see below
  destination:    [u8;8]   // DeviceId of recipient
  created_at_ms:  u64
  ttl_ms:         u32
  sequence:       u64      // per-origin monotonic, replay protection
  payload:        bytes    // Olm ciphertext, padded to a size bucket
  origin_sig:     [u8;64]  // Ed25519 over every field above

  // — mutable, bounds-checked at every hop, NOT signed —
  hop_count:      u8       // reject if > MAX_HOPS (8)
  copies:         u8       // reject if > SPRAY_L or > previous hop's value
}
```

#### Why the public key travels in the frame

An earlier draft of this spec required every hop to verify `origin_sig` against "the known Ed25519 key for `origin`". That is impossible and it would have killed the product: a relay in a crowd has never met the people whose frames it carries, so it holds no key for them, so **every relay would drop every stranger's frame** — and relaying is the entire point.

Carrying the 32-byte public key inside the signed region makes verification **self-contained**. Any device can check any frame with no prior knowledge. The cost is 32 bytes per frame against a minimum payload bucket of 64 bytes and a typical frame of a few hundred; the radio is the bottleneck here, not the arithmetic.

This creates **two distinct levels of trust**, and keeping them distinct is what makes the design work:

| Level | Who checks | What it proves |
|---|---|---|
| **Integrity** | every hop | The frame was not altered in transit, and its routing header, payload and key are bound together by a signature only the holder of that private key could produce |
| **Authenticity** | destination only | That key belongs to a contact I actually added by scanning their QR code |

A relay therefore proves *"this frame is internally consistent and untampered"* — which is all it needs, and all it can honestly know. Only the destination asks *"and is this a person I know?"*, by matching `origin_pubkey` against its contact table.

`origin` must equal the first 8 bytes of `SHA-256(origin_pubkey)`. This binds the short wire id to the key it claims, and it is a cheap hash rather than a signature check, so it runs early.

**What this deliberately does not prevent:** anyone can generate a fresh keypair and emit well-formed frames. Self-contained verification cannot stop that, and no signature scheme can — it is the Sybil problem, and it is bounded rather than solved, by `MAX_HOPS`, the spray budget, the bounded queue, and per-peer rate limiting (Phase 8). This is recorded as an accepted risk in §10 rather than papered over.

**Validation order.** Fail any check and the frame is dropped and counted. Steps 1–6 run at **every hop**; step 7 runs at the **destination only**:

1. `version` known
2. Frame size within `MAX_FRAME_BYTES`
3. `hop_count <= MAX_HOPS`, `copies <= SPRAY_L`
4. `created_at_ms + ttl_ms > now` (not expired)
5. `frame_id` not in the seen-set (dedup)
6. `origin == SHA-256(origin_pubkey)[0..8]`, then `origin_sig` verifies against `origin_pubkey`
7. **Destination only:** `origin_pubkey` matches a known contact, and `sequence` is greater than the highest seen for that origin within a window of 1024

Signature verification sits at step 6, **after** the cheap checks, so malformed garbage cannot force Ed25519 work on every peer. The ordering is deliberate and is tested directly (Phase 2 with a spy, Phase 8 under flood).

Replay protection lives at step 7 rather than at every hop because per-origin sequence state for strangers is exactly the unbounded memory an attacker would target. Relays deduplicate on `frame_id` alone, which is bounded and expires.

### 6.4 Size buckets

Payloads are padded to the next of 64, 256, 1024, 4096 bytes. Ciphertext length otherwise leaks message length to every relay.

### 6.5 Bloom filter

256 bits, k = 2, over frame IDs held in the spray queue, carried in the extended advertisement so a scanner can skip a pointless GATT connection.

Hashes are **two independently keyed SipHash-2-4 instances**, keys fixed at compile time and distinct. The PDF's `h1 = digest mod 256` / `h2 = (digest >> 8) mod 256` both read the low 16 bits of one value; they are correlated, so the true false-positive rate is worse than its table claims. The published rates for a correct 256-bit, k=2 filter are 0.56% at 10 messages, 4.37% at 30, 10.46% at 50, 29.39% at 100 — and Phase 4 measures ours against those numbers rather than trusting them.

A match means "this peer **may** hold a frame I need." A miss means "it definitely does not."

### 6.6 Routing: binary spray-and-wait

```
spray_decision(copies_held, peer_id, destination_id):
    if peer_id == destination_id:  return copies_held   # hand over everything
    if copies_held <= 1:           return 0             # wait phase, hold last copy
    return floor(copies_held / 2)                       # binary split
```

`SPRAY_L` defaults to **8** and is configurable. The PDF's 32 ("higher than the standard 16") is doubly wrong: *L* is a free parameter in Spyropoulos et al., not a standard constant, and a higher *L* costs battery and storage on every device in the crowd. Phase 4 sweeps *L* in the simulator and picks the value from measured delivery rate against overhead.

SoS epidemic flooding is **not** in V1 — it belongs with the deferred medical dispatch work.

## 7. Data model

SQLite via `expo-sqlite`. WatermelonDB was rejected as an unnecessary abstraction over five tables (YAGNI).

| Table | Holds | Notes |
|---|---|---|
| `identity` | Pickled vodozemac account | One row. Pickle key in Android Keystore |
| `contacts` | Ed25519 + Curve25519 keys, display name, DeviceId | Signature verified before insert |
| `sessions` | Pickled Olm sessions, per contact | Ratchet state; corruption here loses history |
| `messages` | Plaintext body, direction, state, timestamps | State: queued → sent → delivered → failed |
| `frames` | Spray queue: frame bytes, copies, expiry | Evicted by TTL and by capacity |
| `seen_frames` | Frame IDs for dedup, with expiry | Bounded ring, sized in Phase 4 |

## 8. Test strategy

Five levels. Every phase states which apply and what "green" means.

| Level | Tool | Runs where | Gates |
|---|---|---|---|
| **Unit — TypeScript** | Jest (jest-expo) | Any machine, CI | Every commit |
| **Unit — Kotlin** | JUnit + Robolectric | Any machine, CI | Every commit |
| **Simulation** | `FakeMeshEngine` + fast-check property tests | Any machine, CI | Routing correctness, before any radio work |
| **On-device integration** | adb-scripted harness, 2–3 phones | Your desk | End of Phases 3, 4, 5 |
| **UI end-to-end** | Maestro against `FakeMeshEngine` | Emulator, CI | Every phase with UI |

**Rules that hold for every phase:**

1. **TDD, without exception.** The test is written first, run to observe it fail for the stated reason, and only then made to pass. A test that has never failed proves nothing.
2. **No performance claim ships unmeasured.** Delivery rate, hop count, battery drain, false-positive rate — each comes from our harness with the command that produced it, or it does not get stated. This is the discipline whose absence produced the ten defects above.
3. **Every defect fix gets a regression test named for it.** `test_x25519_shared_secret_is_32_bytes`, not `test_crypto_works`.
4. **Phase exit gates are commands, not opinions.** Each phase file lists the exact commands and their expected output.

## 9. Phase map

Each phase ends in something visible on a screen. Phases 0–2 need no Bluetooth at all — Phase 2 delivers a complete working chat app before a single radio is involved.

| # | Phase | Visible at the end | Devices |
|---|---|---|---|
| 0 | Foundations and gates | Diagnostics screen: module loaded, crypto self-test green | 1 |
| 1 | Identity and contacts | Scan a QR, contact appears and persists | 1 |
| 2 | Chat over simulated mesh | Full multi-hop chat working on one device | 1 |
| 3 | Real BLE link layer | Two phones discover each other, live RSSI | 2 |
| 4 | Real mesh messaging | A → C → B relay between real phones | 2–3 |
| 5 | Background and battery | App closed, messages still arrive; measured drain | 2 |
| 6 | Location and radar | Friend's live bearing and distance | 2 |
| 7 | Offline map | MBTiles basemap with live pins in aeroplane mode | 2 |
| 8 | Hardening | Attack suite and fuzzers green | 2 |
| 9 | iOS | Deferred until a device exists | — |

## 10. Open risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| vodozemac Kotlin AAR does not bind cleanly | Medium | Critical | Phase 0 GO/NO-GO gate before anything is built on it. Fallback: own UniFFI wrapper over vodozemac directly |
| BLE throughput too low for useful sync | Medium | High | Phase 3 measures real bytes/sec before Phase 4 designs around it |
| OEM battery managers kill the ForegroundService | High | Medium | Phase 5 tests on each physical device; onboarding guides the user through the exception |
| Android 14+ BLE permission friction | Medium | Medium | Phase 3 handles the full permission matrix explicitly |
| Two devices cannot prove multi-hop | High | Medium | Simulator proves routing; Phase 4 adds a time-shifted two-device relay test |
| **Sybil frame injection** — anyone can generate a keypair and emit valid frames | High | Medium | **Accepted, bounded not solved.** `MAX_HOPS`, spray budget, bounded queue, per-peer rate limiting (Phase 8). No signature scheme prevents this in an open mesh; claiming otherwise would be dishonest |
| UniFFI is MPL-2.0, outside the stated permissive allow-list | Certain | Low | **Decided:** MPL-2.0 is file-level copyleft and does not affect a closed product that merely links it. Added to the `cargo-deny` allow-list with this rationale rather than discovered at CI time |
| Native Expo modules (`expo-sqlite`, `expo-crypto`) cannot run under jest | Certain | High | Storage and hashing are accessed through injected interfaces so tests run against Node implementations; native modules are used only in production wiring |

## 11. Audit history

This specification was itself audited on 2026-08-20 by independent reviewers given only the file and told to refute it. The audit found a **design contradiction, not a typo**: §6.3 originally required every hop to verify `origin_sig` against "the known Ed25519 key for `origin`", which is unsatisfiable for a relay carrying a stranger's frame and would have made multi-hop relaying — the entire product — impossible. It would have surfaced in Phase 4, on hardware, after weeks of work.

The fix is the two-level trust model now documented in §6.3: the public key travels inside the signed region, integrity is verified everywhere, authenticity only at the destination.

Recording this here because the same discipline that caught it is the discipline this document was written to enforce: **a specific, confident, plausible claim that nobody checked is exactly the failure mode of the source PDFs** (§4). Being the corrected document did not make this one immune.
