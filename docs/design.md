# MoshGrid V1 — Design Specification

**Status:** approved for planning; scope revised 2026-11-22 (§0)
**Date:** 2026-08-20, revised 2026-11-22
**Scope:** V1 only. Later scope is listed under Non-Goals with the phase that revisits it.
**Screens:** the design canvas "MoshGrid — Brand & App Design" (App screens page). The board "V1 gaps, Help alerts that work in a crowd, meeting points" holds the screens added for this revision.

---

## 0. Scope revision, 2026-11-22

The owner set the V1 core as: **chat, network switching between devices, meeting points, the map, location sharing, and finding each other.** Help alerts were then added to the core. Everything else in the designs (lineup and plan, festival packs beyond the map, groups, the platform console, reporting, crowd counts, themes beyond the default) is secondary and not V1.

What changed in this document:

| Area | Change | Section |
|---|---|---|
| Message status | Adds **seen**, and the `sent` state now records which network carried it. Delivered and seen are encrypted receipts; the plaintext ack frame is retired | §6.7 |
| Meeting points | New. Set from any chat or the map, sent as an encrypted message, no festival pack needed | §6.8 |
| Help alerts | Moved from non-goal into V1: to friends, and optionally to people nearby | §6.9 |
| Network switching | **Decided:** internet through a MoshGrid relay server when it works, Bluetooth mesh always; each can be turned off by the user, and with internet off the app is fully offline | §6.10 |
| Data model, phases, risks | Updated to match | §7, §9, §10 |

## 1. What we are building

An Android app that lets people find and message their friends at a festival **when the cell network is dead**, by relaying messages phone-to-phone over Bluetooth Low Energy.

V1 delivers these, on two or more physical Android phones. Messages go over the internet through a relay server when the phone has a connection and the user allows it, and phone to phone over Bluetooth otherwise (§6.10). **Everything below must still work with internet turned off**:

1. **Identity and contacts** — generate a keypair on device, add a friend by scanning their QR code.
2. **Encrypted 1:1 chat** — text messages, end-to-end encrypted, delivered over the internet or the BLE mesh, whichever works, with store-and-forward when the recipient is out of reach. Every outgoing message shows its status: waiting, sent, delivered, seen, or not sent (§6.7).
3. **Find your friend** — who is in range right now, and which direction and how far away they are, on a radar view and on an offline map. Location sharing is per friend and stops by itself.
4. **Meeting points** — pick a spot (where I am, on the map, or a named place) and send it to one or more friends, who get an arrow to it (§6.8).
5. **Help alerts** — ask friends for help, and optionally let people nearby find you, with what to look for and the nearest medical point (§6.9).

## 2. Non-goals for V1

| Deferred | Why | Revisited in |
|---|---|---|
| iOS | No test device available. A backgrounded iPhone's service UUIDs sit in an overflow area Android cannot see, and `CBPeripheralManager` cannot advertise manufacturer data at all — so iOS cannot be a reliable relay. See §3.4 | Phase 9 |
| Accounts, push, server features beyond relaying | V1 has a relay server (§6.10) but no accounts: a phone proves it owns its `DeviceId` by signing. Push notifications, publishing festivals and voting (§12.2) stay out of V1 | Post-V1 |
| Medical dispatch SoS | Safety-critical and regulated; needs a signed medical provider and a proven mesh first. **Not the same as Help alerts (§6.9)**, which are peer-to-peer and in V1: nothing in V1 dispatches staff or medics | Own spec cycle |
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

**Known issue, handled:** in February 2026 a researcher reported that vodozemac does not reject all-zero X25519 Diffie-Hellman outputs (non-contributory keys). Matrix disputes exploitability — identity keys and pre-keys are Ed25519-signed and verified before session establishment — and has agreed to add the check as defence in depth. **We apply the check ourselves rather than waiting for the upstream release** (Phase 1, Task 2).

### 3.3 The security lesson that shapes the architecture

Bridgefy was broken in 2020 with custom cryptography. It adopted libsignal. It was **broken again in 2022** — the paper is titled *"Breaking Bridgefy, again: Adopting libsignal is not enough."*

The conclusion is not "pick a better crypto library." It is that **mesh-layer framing and metadata are a distinct attack surface below the crypto layer**, and encrypting the payload does nothing for it. So V1 treats the mesh layer as adversarial by design:

- Every frame's **routing header is signed by the origin**, not just its payload, and the origin's public key travels inside the signed region so any hop can check it without prior knowledge. Altering a sender, destination, or timestamp in transit is detectable everywhere.
- **Integrity is checked at every hop; authenticity only at the destination.** A relay can prove a frame is untampered without knowing who sent it — which is the only thing it can honestly know about a stranger. See §6.3.
- Mutable fields (`hop_count`, `copies`) are excluded from the signature but **bounds-checked** at every hop; a frame claiming impossible values is dropped.
- Frame IDs are random and deduplicated, so replay and amplification are bounded.
- Frames are size-bucketed so ciphertext length leaks less about message length.
- The attack suite in Phase 8 tests these properties directly, as tests that must fail before they pass.

What this does **not** claim is a mesh where only known contacts can transmit. Any device can join and emit well-formed frames; that is inherent to an open mesh and is bounded rather than eliminated (§10).

The guarantee is narrower than an earlier draft of this document stated, and the precision matters:

> **Nobody can forge a frame attributed to a specific person, alter its routing header or payload in flight, or read one they are relaying.**

The earlier wording said "alter one in flight", full stop. That was false. `hop_count` and `copies` sit **outside** the signature deliberately, because a relay has to increment them — so any relay can reset `hop_count` to 0 (extending a frame's reach for its whole TTL) or raise `copies` back to `SPRAY_L` (spray amplification), and neither is detectable. They are attacker-controlled at every hop, bounded only by `MAX_HOPS` and `SPRAY_L`. Phase 8 pins this as intended behaviour in a test named for it, so the guarantee sentence has to match.

**The cost of self-contained verification, stated plainly.** §6.1 truncates identity to 8 bytes on the wire on purpose. §6.3 then puts the full 32-byte Ed25519 key in cleartext in every frame, readable by every relay. Contact bundles are public by construction — they are QR codes people hand out. So **anyone who has ever obtained a person's bundle can afterwards confirm that person's presence, passively and with cryptographic certainty, from any frame that person originated.** Not inference from an advert prefix: proof.

That is a real privacy regression, introduced by the fix for the relay problem, and it is recorded in §10 rather than left implicit. The alternative — encrypting the pubkey to the destination — would restore the original problem of relays being unable to verify anything. This design takes the trade deliberately; it should not be taken silently.

### 3.4 Platform: Android first

Android is the only platform where this can work correctly. A `ForegroundService` plus `PendingIntent`-based scanning registers inside the Bluetooth stack, so the registration outlives the app process. (What the app may then *do* with a result is constrained by Doze, which ignores wake locks — see Phase 5. The earlier claim that this design "survives Doze" was too strong.)

iOS cannot be a reliable relay, and the decisive reasons are structural rather than policy:

- **`CBPeripheralManager` cannot advertise manufacturer-specific data at all** — `startAdvertising` supports only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`. The 6-byte presence beacon has nowhere to live on iOS.
- **A backgrounded iPhone's service UUIDs move to an "overflow area"** discoverable "only by an iOS device explicitly scanning for them", and the local name is not advertised at all. An Android phone generally cannot see a backgrounded iPhone.

**A correction, because an earlier draft of this document got it wrong and the error came from a bad source.** That draft said iOS 26 relaunches an app for BLE events *only* if it uses AccessorySetupKit. TN3115 does not say that. The AccessorySetupKit note is attached to **two rows** of its relaunch table — app force-quit by the user, and Control Center Bluetooth toggled. An app terminated by the system or crashed is **still relaunched** by plain state restoration, and so is a rebooted device after first unlock.

The wrong version was near-verbatim a GitHub issue's paraphrase which had dropped the row scoping — and that paraphrase was what our own initial fact-check of the source PDFs relied on. A secondary source that compresses a table into a sentence is exactly where this kind of error is manufactured, and it survived because the sentence sounded authoritative.

TN3115 therefore supports the conclusion but does not carry it. The overflow area and the missing manufacturer payload do. The source PDF's own honest assessment agrees: iOS is an opportunistic contributor, never a relay.

This also matches the available hardware: two or more Android phones, no iPhone.

### 3.5 Framework: Expo with a dev client

React Native UI in TypeScript; the mesh engine as a **Kotlin Expo Module**.

The source PDF rejected Expo on the grounds that "background BLE requires native module modifications Expo does not permit." That is wrong. The Expo Modules API is written for exactly this, dev clients and prebuild allow arbitrary native code, and there are working ForegroundService + WakeLock BLE modules built this way.

Choosing Expo also buys EAS Build, so Gradle and signing are not a per-phase tax.

### 3.6 Offline maps: a local file behind a URL scheme, not a custom source

V1 ships **a single local tile archive per festival**, generated from OpenStreetMap extracts with Planetiler, and points the MapLibre style at it with a URL scheme. No tile provider, no API key, no rate limit, no third-party terms that can change, and **no custom native code**.

MapLibre Native already ships both local schemes and wires them into its default resource loader:

- `mbtiles://file:///absolute/path.mbtiles` — in the Android core since 9.3.0
- `pmtiles://file:///absolute/path.pmtiles` — documented alongside `pmtiles://https://` for remote

**A correction worth recording, because it nearly cost the project a subsystem.** An earlier draft of this spec rejected PMTiles on the grounds that "PMTiles sources do not support offline packs or caching — they need live HTTP range requests." The first half is a verbatim quote from MapLibre's docs. The second half is invented, and false: the offline-pack restriction concerns `OfflineManager`'s tile-pack database, which is irrelevant when the whole archive is already a local file. `pmtiles://file://` is fully offline. (The "or caching" half is also now stale — Android 13.3.0 added an ambient cache for PMTiles sources.)

A real quote with an invented explanation welded onto it is the most dangerous shape a wrong claim can take, because the quote survives spot-checking and the explanation is what gets acted on.

That draft also specified a custom Kotlin `LocalTilesSource`. **No such API exists.** MapLibre Android's source classes are `CustomGeometrySource`, `GeoJsonSource`, `ImageSource`, `RasterDemSource`, `RasterSource`, `VectorSource` and supporting types — and `CustomGeometrySource` accepts parsed GeoJSON features, not raw tile bytes. Custom tile sources cannot be registered from Kotlin on Android at all. The planned work would have reimplemented, in the wrong language and through an API that does not exist, code that already ships in the dependency — including the TMS/XYZ y-flip and the cached database handle.

**Format choice is deferred to a measured spike**, not decided here. Both schemes are local-file capable; what matters is which is compiled into the MapLibre build inside the `@maplibre/maplibre-react-native` version actually installed. Phase 7 Task 1 verifies that on a device before anything is built on it.

`OfflineManager.createPack()` was considered and rejected on grounds that do survive scrutiny: it downloads from a live style URL, requires network at pack-creation time, and makes a tile vendor's terms part of the product.

## 4. Corrections carried from the source PDF

The source PDFs were AI-generated and audited on 2026-08-20. Ten defects were verified. Each is resolved here and enforced by a named test.

| # | PDF claim | Reality | Resolution | Enforced by |
|---|---|---|---|---|
| 1 | X25519 shared secret is 128 bytes | 32 bytes (RFC 7748) | Sizes asserted in code | Phase 1, Task 2 |
| 2 | libsignal, cost "Free" | AGPL-3.0-only | vodozemac (Apache-2.0) | Phase 0, Task 5 (CI licence gate) |
| 3 | Bridgefy failed due to custom crypto | Broken again *after* adopting libsignal | Signed routing headers | Phase 8, Task 2 |
| 4 | Bloom `h1 = d mod 256`, `h2 = (d>>8) mod 256` | Both read the low 16 bits — correlated, real FP rate exceeds the table | Two independently keyed SipHash instances | Phase 2, Task 3 |
| 5 | 31-byte advertisement payload | 31 bytes is the **total** AD budget including per-element headers; ~6 bytes are usable | Legacy advert is a presence beacon only | Phase 3, Task 1 |
| 6 | Advertising at 211 ms | `ADVERTISE_MODE_BALANCED` is 250 ms | Use named constants, never magic numbers | Phase 3, Task 1 |
| 7 | Expo cannot do background BLE | Expo Modules API is built for it | Expo + dev client | Phase 0, Task 2 |
| 8 | Spray copies L=32, "standard is 16" | *L* is a free parameter in the literature, not a constant | L is configurable, default 8, measured | Phase 2 Task 4 (test), Phase 4 Task 4 (sweep) |
| 9 | Delivery rates 92–94%, overhead 3.2× | Uncited and simulation-dependent | No performance number ships unmeasured | Phase 4, Task 4 |
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
  frame_type:     u8       // 1=data 2=reserved (was ack, §6.7) 3=digest_req 4=digest_resp
                           // 5=help_broadcast 6=help_cancel (§6.9)
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
  copies:         u8       // reject if > SPRAY_L. NOT '> previous hop's value':
                           // there is no signed hop chain, so the only knowledge
                           // of the previous value is the field being checked.
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
7. **Destination only:** `origin_pubkey` matches a known contact, and `sequence` passes the replay window (below)

Signature verification sits at step 6, **after** the cheap checks, so malformed garbage cannot force Ed25519 work on every peer. The ordering is deliberate and is tested directly (Phase 2 with a spy, Phase 8 under flood).

**Step 5 reads the seen-set; it must not write to it.** A `frame_id` is recorded only **after step 6 passes**. Inserting on first sight would let any device with no key material at all flood random 16-byte ids and evict genuine entries from a bounded ring — turning the replay defence into the memory-exhaustion vector, and re-enabling the routing loops dedup exists to stop.

**Replay window.** Per-origin state is kept only for known contacts, at the destination — tracking sequences for strangers is exactly the unbounded memory an attacker would target. Relays deduplicate on `frame_id` alone, which is bounded and expires.

The window is a **highest-seen value plus a 1024-bit acceptance bitmap**, persisted per contact:

- `sequence > highest` → accept, advance `highest`, shift the bitmap
- `highest - 1024 < sequence <= highest` → accept **only if** its bitmap bit is unset, then set it
- `sequence <= highest - 1024` → reject as `replay`

A plain "greater than the highest seen" rule was specified in an earlier draft and is wrong twice over: it contradicts §3.2's requirement to tolerate long-delayed, out-of-order delivery — the property the entire cryptographic choice was made for — and it would drop every legitimately late frame as an attack. A bitmap accepts late frames once and replays never.

This state needs somewhere to live. `contacts` carries `highest_sequence INTEGER` and `replay_bitmap BLOB`; see §7.

### 6.4 Size buckets

Payloads are padded to the next of 64, 256, 1024, 4096 bytes. Ciphertext length otherwise leaks message length to every relay.

### 6.5 Bloom filter

256 bits, k = 2, over frame IDs held in the spray queue, carried in the extended advertisement so a scanner can skip a pointless GATT connection.

Hashes are **two independently keyed SipHash-2-4 instances**, keys fixed at compile time and distinct.

**The plan currently implements seeded FNV-1a instead, and that is a real divergence, not a detail.** FNV is not a keyed PRF, so a seeded pair is not "independently keyed" in the cryptographic sense. Because the digest rides in a public advertisement, an attacker can grind frame ids to force collisions and suppress sync between honest peers — and the chi-squared independence test in Phase 2 would not catch it, because the distribution is fine; it is the *unpredictability* that is missing. Either the plan adopts SipHash-2-4, or this paragraph is downgraded to "two seeded non-cryptographic hashes whose independence is measured" and the adversarial-collision risk is carried in §10. Do not leave spec and plan disagreeing. The PDF's `h1 = digest mod 256` / `h2 = (digest >> 8) mod 256` both read the low 16 bits of one value; they are correlated, so the true false-positive rate is worse than its table claims. The published rates for a correct 256-bit, k=2 filter are 0.56% at 10 messages, 4.37% at 30, 10.46% at 50, 29.39% at 100 — and Phase 4 measures ours against those numbers rather than trusting them.

A match means "this peer **may** hold a frame I need." A miss means "it definitely does not."

### 6.6 Routing: binary spray-and-wait

```
spray_decision(copies_held, peer_id, destination_id):
    if peer_id == destination_id:  return copies_held   # hand over everything
    if copies_held <= 1:           return 0             # wait phase, hold last copy
    return floor(copies_held / 2)                       # binary split
```

`SPRAY_L` defaults to **8** and is configurable. The PDF's 32 ("higher than the standard 16") is doubly wrong: *L* is a free parameter in Spyropoulos et al., not a standard constant, and a higher *L* costs battery and storage on every device in the crowd. Phase 4 sweeps *L* in the simulator and picks the value from measured delivery rate against overhead.

SoS epidemic flooding to the whole crowd is **not** in V1. Help alerts to people nearby (§6.9) use a hop-limited broadcast, which is a different, bounded thing.

### 6.7 Message content, status and receipts

Everything a friend sends a friend travels as a `data` frame. Inside the Olm ciphertext, the plaintext is a small typed record:

```
Content {
  kind:        u8     // 1=text 2=receipt 3=location 4=meet 5=meet_reply 6=help (to a friend)
  message_id:  [u8;16]
  body:        kind-specific (below)
}
receipt    { of: [message_id], level: delivered | seen }
location   { lat, lon, accuracy_m, fix_at_ms, share_until_ms }
meet       { meet_id, op: set | move | clear, lat, lon, name?: string ≤ 48 bytes, set_at_ms }
meet_reply { meet_id, reply: on_my_way | elsewhere }
```

Because the kind is inside the ciphertext, and every frame is padded to a size bucket (§6.4), **a relay cannot tell a receipt from a message.** That is why receipts are not their own frame type.

**The plaintext `ack` frame (type 2) is retired.** It told every relay on the return path that the destination had received something, and when. A `seen` receipt sent that way would also tell them when the person opened the chat. Type 2 stays reserved so old frames are dropped, not misread.

**Message states:**

| State | Meaning | Shown as |
|---|---|---|
| `queued` | On this phone, no route yet | Clock, amber: “Waiting to send” |
| `sent` | Handed to at least one other phone, or stored by the relay server (§6.10). `via` records which | One tick, grey: “Sent through phones nearby” / “Sent over the internet” |
| `delivered` | A `delivered` receipt arrived | Two ticks, grey |
| `seen` | A `seen` receipt arrived | Two ticks, accent colour, and the word “Seen” |
| `failed` | TTL expired with no `delivered` receipt, or sending was refused | Red mark: “Not sent. Tap to try again” |

States only move forward. A late `delivered` after `seen` is ignored.

**Seen receipts are reciprocal and on by default.** Turning them off stops sending them and stops showing them, so nobody gets other people's read state while hiding their own. `delivered` receipts are always sent; without them the sender cannot tell a lost message from a slow one.

The chat list shows the last message with the same icon. Status is never colour alone: icon plus words, read out by screen readers.

### 6.8 Meeting points

A meeting point is a `meet` record (§6.7) sent in a 1:1 chat. Sending one to several friends sends one record per chat, with the same `meet_id`. It needs no festival pack: a pack only adds named places to pick from.

- **One live meeting point per chat.** `set` replaces any earlier one, `move` changes it, `clear` removes it. When two arrive for the same `meet_id`, the one with the later `set_at_ms` wins, ties broken by the higher `DeviceId`.
- Ways to set one: **Meet where I am** (current fix), **pick a spot on the map** (pin under a fixed crosshair, or a long-press anywhere on the map), or **pick a named place** if the festival map has them.
- The receiver gets **Find it** (arrow and distance, as for a friend), **On my way** (sends `meet_reply` and starts location sharing with that friend, for the default sharing duration) and **Somewhere else** (opens the same picker and replies with a new `set`).
- The live meeting point is pinned at the top of the chat and at the top of the Find list, and shows on the map and the compass.
- It works offline: the map is local (§3.6), and the arrow needs only two fixes.

### 6.9 Help alerts

Two audiences, two mechanisms.

**To friends.** A `help` record (§6.7) sent to each friend in a normal encrypted frame, plus location updates while the alert is on. Friends see who it is. This is ordinary 1:1 messaging with a higher priority in the send queue.

**To people nearby (opt-in, per alert).** A `help_broadcast` frame (type 5), readable by any MoshGrid phone. It must not reveal who sent it, so it is **signed with a fresh one-time key made for this alert**, not the person's identity key. §3.3's self-contained verification still holds: the frame carries that one-time public key and is signed by it, so relays can check it was not altered. Nothing links it to the person's code, so strangers do not learn who it is, and holders of the person's code cannot confirm it is them (the problem recorded in §10 does not extend to Help).

```
help_broadcast payload {
  alert_id:      [u8;16]
  lat, lon, accuracy_m
  updated_at_ms
  expires_at_ms           // at most 15 minutes after the alert started
  look_for?:     string   // ≤ 96 bytes: “red top, cap, by the bar”
  responders:    u8       // how many people said they are going, for the sender's screen
}
```

- **Reach is bounded.** It is relayed only a small number of hops (a constant, chosen by simulation, not guessed), and expires with the alert. It is never sprayed crowd-wide.
- **Stopping it.** “Only friends again” or ending the alert sends `help_cancel` (type 6), signed with the same one-time key. Receivers remove the spot from their screens.
- **Getting found in a crowd.** The sender can add what to look for, and turn the whole screen into a steady bright colour (it never flashes), with the torch on. A helper sees the arrow, the distance, and what to look for, and can ask the sender to light their screen.
- **Nearest help.** Both sides see the nearest medical and welfare points when the festival map has them, and always a “Call 112” option. Without a map: move toward a stage or crowd.
- **Limits.** One alert per 10 minutes, 3 per hour, 5 per day, each ending after 15 minutes (the rules on the Help alert design board). These are enforced on the sending phone, so a modified app can ignore them. See §10.
- **Who answers.** Responding is optional and anonymous. The helper's screen says that if it doesn't feel safe to go, they should tell a steward or security instead.

### 6.10 Network switching: internet and Bluetooth

Decided 2026-11-22. Two links carry the **same signed, encrypted frames** (§6.3):

| Link | Carries frames | Used when |
|---|---|---|
| `InternetLink` | To and from the MoshGrid relay server | The user allows internet **and** the phone has a working connection |
| `BleLink` | Phone to phone (§6.6) | The user allows phones nearby **and** Bluetooth is on and permitted |

**The user decides which links may be used**, first during onboarding (“How should messages travel?”, after permissions) and any time later on the Network screen: two checkboxes, both ticked by default.

- **Internet unticked = offline only.** MoshGrid makes no network connection at all: no uploads, no inbox checks, nothing to the server. Every feature still works over Bluetooth.
- **Phones nearby unticked = internet only.** With no connection, messages wait.
- **At least one stays ticked.** Unticking the last one is refused with a message, because a phone with no link can only queue.

**Switching is automatic and needs no user action.** The engine listens to the operating system's connectivity changes and checks the server is reachable; Bluetooth availability comes from the link layer.

**Sending.** The frame is built once.

1. If `InternetLink` is usable, upload it. When the server confirms it is stored, the message becomes `sent` with `via = internet`.
2. If `BleLink` is allowed, the frame also enters the spray queue (§6.6). After a successful upload it is **held back for a grace period** before spraying; a `delivered` receipt inside that period removes it from the queue, so the crowd's capacity isn't spent on messages the internet already delivered. If the upload fails or there is no connection, it sprays at once. The grace period is a configurable constant chosen by measurement in Phase 12, not guessed here.
3. Receipts (§6.7) travel the same way.

**Receiving.** With internet usable, the phone keeps a connection to the server and collects frames addressed to it. Bluetooth keeps listening whenever it is allowed. **One seen-set covers both links**, so a frame that arrives twice is processed once (§6.3, step 5).

**Bridging.** If the user passes on messages for others and internet is usable, frames carried for other people are also uploaded. A message can then cross from the crowd to the internet at any phone with signal. Relays still see only ciphertext.

**The relay server:**

- Stores frames by destination `DeviceId`, deletes each on delivery or when its TTL ends.
- **No accounts.** To collect its frames, a phone signs a fresh server challenge with its Ed25519 key; because `DeviceId` is the hash of that key (§6.1), the server needs no registration step.
- Validates frames with the same checks 1–6 as any relay (§6.3) before storing them, and rate-limits per connection.
- Sees, and must be treated as seeing: origin public key, destination id, time, size bucket, and the phone's IP address. **Never** message content. Recorded in §10.
- Its dependencies follow the same licensing policy (§3.1).

## 7. Data model

SQLite via `expo-sqlite`. WatermelonDB was rejected as an unnecessary abstraction over six tables (YAGNI).

| Table | Holds | Notes |
|---|---|---|
| `identity` | Pickled vodozemac account | One row. Pickle key in Android Keystore |
| `contacts` | Ed25519 + Curve25519 keys, display name, DeviceId | Signature verified before insert |
| `sessions` | Pickled Olm sessions, keyed by `session_id` with a `device_id` index | **Not one row per contact.** Olm routinely yields several concurrent sessions for one device — simultaneous initiation, or an inbound pre-key message arriving while an outbound session exists. Keying on `device_id` would let a newer session overwrite an older one, making every frame still in flight under the old ratchet permanently undecryptable: precisely the hours-delayed case §3.2 exists to support. Decrypt by trying each session for the device |
| `messages` | Plaintext body, `kind` (§6.7), direction, state, `via`, timestamps | State: queued → sent → delivered → seen; or failed (§6.7). Receipts update the row they refer to and are not stored as messages |
| `meeting_points` | `meet_id`, contact `device_id`, lat, lon, name, set_by, set_at, cleared_at | One live row per chat (§6.8) |
| `help_alerts` | Own and received alerts: `alert_id`, one-time public key, location, `look_for`, expiry | Own one-time private key is deleted when the alert ends. Received alerts are deleted at expiry |
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
| 10 | Meeting points | Set a spot from a chat or the map; friend gets an arrow; works in aeroplane mode | 2 |
| 11 | Help alerts | Friends get the alert; a stranger's phone shows an anonymous spot, what to look for, and the nearest medical point | 2–3 |
| 12 | Internet relay and switching | Same chat works over the server with signal and over Bluetooth without; untick internet and nothing touches the network | 2 |

**Changes to existing phases from the 2026-11-22 revision** (the plan files have not been updated yet):

- **Phase 2:** `MessageState` gains `seen`; messages carry `via`; delivery uses encrypted receipts (§6.7), not a type-2 ack frame; the chat list shows the last message's status; the Seen setting.
- **Phase 4:** the “ack propagates back” test becomes “a delivered receipt propagates back”.
- **Phase 6:** location sharing must also carry meeting-point fixes (§6.8).
- **Phase 7:** long-press on the map opens the meeting-point sheet.

## 10. Open risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| vodozemac Kotlin AAR does not bind cleanly | Medium | Critical | Phase 0 GO/NO-GO gate before anything is built on it. Fallback: own UniFFI wrapper over vodozemac directly |
| BLE throughput too low for useful sync | Medium | High | Phase 3 measures real bytes/sec before Phase 4 designs around it |
| OEM battery managers kill the ForegroundService | High | Medium | Phase 5 tests on each physical device; onboarding guides the user through the exception |
| Android 14+ BLE permission friction | Medium | Medium | Phase 3 handles the full permission matrix explicitly |
| Two devices cannot prove multi-hop | High | Medium | Simulator proves routing; Phase 4 adds a time-shifted two-device relay test |
| **Sybil frame injection** — anyone can generate a keypair and emit valid frames | High | Medium | **Accepted, bounded not solved.** `MAX_HOPS`, spray budget, bounded queue, per-peer rate limiting (Phase 8). No signature scheme prevents this in an open mesh; claiming otherwise would be dishonest |
| **Presence is confirmable by anyone holding a contact bundle** — `origin_pubkey` is a permanent, globally unique, cryptographically verifiable identifier sent in cleartext to every relay | Certain | **High** | **Accepted deliberately**, as the price of self-contained relay verification (§3.3). Bundles are public QR codes, so this is not a hypothetical. Mitigating it means encrypting the pubkey to the destination, which re-breaks relay verification. Must be disclosed to users, and revisited if a rotating-identity design becomes viable |
| **`hop_count` and `copies` are attacker-controlled at every hop** | High | Medium | Unsigned by necessity — relays must increment them. Bounded by `MAX_HOPS` and `SPRAY_L` only. A hostile relay can extend reach and amplify spray undetectably |
| Bloom digest hashes are non-cryptographic and the digest is public | Medium | Medium | An attacker can grind frame ids to force collisions and suppress sync. Use SipHash-2-4 with distinct keys, or state the exposure explicitly (§6.5) |
| UniFFI is MPL-2.0, outside the stated permissive allow-list | Certain | Low | **Decided:** MPL-2.0 is file-level copyleft and does not affect a closed product that merely links it. Added to the `cargo-deny` allow-list with this rationale rather than discovered at CI time |
| Native Expo modules (`expo-sqlite`, `expo-crypto`) cannot run under jest | Certain | High | Storage and hashing are accessed through injected interfaces so tests run against Node implementations; native modules are used only in production wiring |
| **Fake Help alerts from people nearby** — rate limits are enforced on the sending phone only | High | Medium | A modified app can ignore them. Bounded by the hop limit, the 15-minute expiry and per-peer rate limiting on relays (Phase 8). Receivers can mute a stranger's alerts for 24 hours. Reporting is a server feature outside the V1 core |
| **A Help alert tells people nearby where a vulnerable person is** | Certain | High | Opt-in per alert, never the default; no name; one-time key so it cannot be linked to the person; “Only friends again” without ending the alert; ends after 15 minutes |
| **Help alerts reach few people early on** — strangers can only help if they run MoshGrid in Bluetooth range | High | High | The alert keeps retrying and is relayed when a phone comes in range; the sender always sees the nearest medical point and “Call 112”. Measure real reach in Phase 11 rather than promise it |
| **The relay server sees who talks to whom, and when** — origin key, destination, timing and IP for every frame it handles | Certain | High | Content stays end-to-end encrypted. The server keeps frames only until delivery or TTL and keeps no logs beyond what abuse limits need. Users who don't accept this can untick internet and stay fully offline (§6.10). Disclosed on the About page |
| **Seen receipts leak reading habits** | Low | Low | Sent inside the ciphertext, indistinguishable from messages to relays (§6.7). Reciprocal off switch |

## 11. Audit history

This specification was audited on 2026-08-20 by independent reviewers given only the file and told to refute it — first indirectly, while the plans were being audited, and then directly. The first pass found a **design contradiction, not a typo**: §6.3 originally required every hop to verify `origin_sig` against "the known Ed25519 key for `origin`", which is unsatisfiable for a relay carrying a stranger's frame and would have made multi-hop relaying — the entire product — impossible. It would have surfaced in Phase 4, on hardware, after weeks of work.

The fix is the two-level trust model now documented in §6.3: the public key travels inside the signed region, integrity is verified everywhere, authenticity only at the destination.

Recording this here because the same discipline that caught it is the discipline this document was written to enforce: **a specific, confident, plausible claim that nobody checked is exactly the failure mode of the source PDFs** (§4). Being the corrected document did not make this one immune.

## 12. Open decisions

### 12.1 Network switching — decided 2026-11-22

Option A was chosen: internet through a relay server when it works, Bluetooth mesh always, with each link switchable by the user and a fully offline mode. Specified in §6.10. Options B (Wi-Fi Direct between phones) and C (both) were not chosen; B can be added later as a third link without changing frames.

### 12.2 Festivals added by people, shared publicly and rated — needs a server

Requested 2026-11-22: people can add a festival themselves (name, dates, place, lineup, and an uploaded image such as the poster or site map), publish it so others can reuse it, and up- or down-vote it, with festivals listed by city and sorted by votes.

Publishing, voting and a city list need a shared server. V1 now has one (§6.10), but only as a relay; this feature is still **not** in the V1 core (§0). What can ship without a server: adding a festival for yourself, and sharing it with friends as a message.

Open questions for when it is planned: who can edit a published festival; how a vote is limited to one per person without accounts (one per identity key is cheap to fake, see the Sybil risk in §10); and how published festivals are moderated (the platform console designs cover removing scam festivals).
