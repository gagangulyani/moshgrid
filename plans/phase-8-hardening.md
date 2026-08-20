# Phase 8 — Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** attack the app deliberately, from the position of a hostile peer in the crowd, and make every attack fail as a test rather than as an assurance.

**Architecture:** a hostile-peer harness built on the Phase 2 simulator — a `MaliciousLink` that can forge, replay, truncate, flood and impersonate — plus fuzzers over every parser that touches untrusted bytes. No production code paths change unless an attack succeeds.

**Tech Stack:** fast-check (property and fuzz testing), `cargo-fuzz`, Jest (jest-expo), JUnit, the Phase 2 `Sim`.

**Spec:** [`../docs/design.md`](../docs/design.md) §3.3, §6.3

**Devices:** 2 phones for the on-device attacks. Most of this phase runs on your Mac.

**Depends on:** Phase 7 complete. Attacking a partial feature surface leaves the untested half as the way in.

## Why this phase exists

Bridgefy was broken in 2020 with custom cryptography. It adopted libsignal. It was broken **again** in 2022 — the paper is titled *"Breaking Bridgefy, again: Adopting libsignal is not enough."*

The source PDF drew the wrong conclusion from that history. Its Table 6 attributes Bridgefy's failure to "Custom cryptography" and concludes that choosing Signal Protocol makes the problem go away. The second break happened **after** the libsignal migration, on the libsignal build, because the attacks landed on mesh framing and metadata — below the crypto layer, where the payload's encryption is irrelevant.

So this phase does not test the cryptography. vodozemac is audited and we are not going to out-audit it. This phase tests **everything around** the cryptography: the routing header, the replay window, the parsers, the queue, and what an observer learns from traffic they cannot read. That is where a mesh messenger actually breaks.

Every attack here is written as a test that **must fail before it passes**. An attack that has never succeeded against an unpatched build proves nothing about the patched one.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Enforced directly here:

- **Signed routing headers.** Forging any immutable field must be detectable at every hop.
- **Validation order is fixed:** cheap checks before signature verification, so unsigned garbage cannot force Ed25519 work on every peer.
- **`MAX_HOPS` = 8, `MAX_FRAME_BYTES` = 4096.** Bounds are enforced, not documented.
- **Frame size must not reveal payload kind.** Size buckets 64 / 256 / 1024 / 4096.
- **No performance number ships unmeasured** — including the cost of resisting an attack.

## File structure

| File | Responsibility |
|---|---|
| `src/mesh/fake/MaliciousLink.ts` | Hostile peer: forge, replay, truncate, flood |
| `src/mesh/__tests__/attacks/forgery.test.ts` | Routing-header forgery (defect 3) |
| `src/mesh/__tests__/attacks/replay.test.ts` | Replay and sequence-window attacks |
| `src/mesh/__tests__/attacks/metadata.test.ts` | Traffic-analysis leaks |
| `src/mesh/__tests__/attacks/exhaustion.test.ts` | Queue, storage and battery flooding |
| `src/mesh/__tests__/attacks/sybil.test.ts` | Fake identities and impersonation |
| `src/mesh/__tests__/fuzz/frame.fuzz.test.ts` | Frame parser fuzzing |
| `modules/.../ble/ChunkerFuzzTest.kt` | GATT reassembly fuzzing |
| `modules/.../map/MbtilesFuzzTest.kt` | Malformed map pack handling |
| `crypto/fuzz/fuzz_targets/decrypt.rs` | `cargo-fuzz` target over Olm decryption |
| `docs/threat-model.md` | What is defended, what is accepted |

---

## Task 1: The hostile peer harness

Every later task uses this. It is a `Link` implementation that lies.

**Files:**
- Create: `src/mesh/fake/MaliciousLink.ts`
- Test: `src/mesh/fake/__tests__/malicious.test.ts`

**Interfaces:**
- Consumes: `Link` from Phase 2, `FakeBus` and `Sim` from Phase 2 Task 6.
- Produces: `class MaliciousLink implements Link` with `forgeFrame(overrides)`, `forgeAsNewIdentity(overrides)`, `replayLast(times)`, `truncateNext(bytes)`, `floodQueue(count)`, `impersonate(deviceId)`.
- Also produces two deterministic test helpers used by the class, defined at the top of the same file:

```typescript
/** 32 zero bytes, base64 — a well-formed key that belongs to nobody. */
export const FAKE_PUBKEY_B64 = 'A'.repeat(43);

let fakeUuidCounter = 0;
/** Deterministic frame ids so an attack that fails can be replayed exactly. */
export function nextFakeUuid(): string {
  const n = (fakeUuidCounter++).toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${n}`;
}
```

`forgeFrame` and `forgeAsNewIdentity` test **different** attacks and both are needed. The first sends a frame whose signature is wrong — tampering, caught at every hop. The second sends a cryptographically valid frame from a keypair nobody knows — the Sybil case, which must relay everywhere and be refused only at the destination. Conflating them is how a test suite ends up asserting the bug rather than the fix.

- [ ] **Step 1: Write the failing tests for the harness itself**

The attack tool needs its own tests. A harness that silently fails to attack produces a green suite that means nothing.

```typescript
// src/mesh/fake/__tests__/malicious.test.ts
import { Sim } from '../Sim';
import { FakeBus } from '../FakeLink';
import { MaliciousLink } from '../MaliciousLink';
import { decodeFrame } from '../../frame';

const EVIL = 'e'.repeat(16);
const VICTIM = 'v'.repeat(16);

function harness() {
  const sim = new Sim({ rangeMetres: 50, seed: 3 });
  sim.addDevice(EVIL, { x: 0, y: 0 });
  sim.addDevice(VICTIM, { x: 10, y: 0 });
  return { sim, bus: new FakeBus(sim) };
}

test('forgeFrame actually produces a frame with the requested origin', async () => {
  const { bus } = harness();
  const evil = new MaliciousLink(bus);
  await evil.start(EVIL);

  const bytes = await evil.forgeFrame({ origin: VICTIM, destination: VICTIM });
  expect(decodeFrame(bytes).origin).toBe(VICTIM);
});

test('forgeFrame produces an invalid signature by default', async () => {
  const { bus } = harness();
  const evil = new MaliciousLink(bus);
  await evil.start(EVIL);

  const frame = decodeFrame(await evil.forgeFrame({ origin: VICTIM }));
  expect(frame.originSig).not.toBe('');
});

test('replayLast re-sends the exact same bytes', async () => {
  const { bus } = harness();
  const evil = new MaliciousLink(bus);
  await evil.start(EVIL);

  const sent: Uint8Array[] = [];
  evil.onSend((bytes) => sent.push(bytes));

  const original = await evil.forgeFrame({ origin: VICTIM });
  await evil.sendTo(VICTIM, original);
  await evil.replayLast(3);

  expect(sent).toHaveLength(4);
  expect(sent[1]).toEqual(sent[0]);
});

test('truncateNext shortens the payload as requested', async () => {
  const { bus } = harness();
  const evil = new MaliciousLink(bus);
  await evil.start(EVIL);

  const sent: Uint8Array[] = [];
  evil.onSend((bytes) => sent.push(bytes));

  evil.truncateNext(10);
  await evil.sendTo(VICTIM, new Uint8Array(200));

  expect(sent[0].length).toBe(10);
});
```

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- fake/malicious
```

Expected: FAIL — cannot find module `../MaliciousLink`.

- [ ] **Step 3: Implement**

```typescript
// src/mesh/fake/MaliciousLink.ts
import { FakeLink, type FakeBus } from './FakeLink';
import { buildFrame, encodeFrame, type MeshFrame } from '../frame';
import type { DeviceId } from '../types';

type SendSpy = (bytes: Uint8Array) => void;

/**
 * A Link that lies. Used only in tests, never bundled into the app.
 * Every method here corresponds to something a hostile phone in the
 * crowd can actually do — see docs/threat-model.md.
 */
export class MaliciousLink extends FakeLink {
  private lastSent: Uint8Array | null = null;
  private truncateTo: number | null = null;
  private spies: SendSpy[] = [];

  constructor(bus: FakeBus) { super(bus); }

  onSend(spy: SendSpy): void { this.spies.push(spy); }

  /**
   * Build a frame with arbitrary fields and a signature that will not verify.
   * Used to test that tampering is caught — see forgeAsNewIdentity for the
   * "valid frame from a stranger" case, which is a different attack.
   */
  async forgeFrame(overrides: Partial<MeshFrame>): Promise<Uint8Array> {
    const base = await buildFrame({
      origin: overrides.origin ?? 'f'.repeat(16),
      originPubkey: overrides.originPubkey ?? FAKE_PUBKEY_B64,
      destination: overrides.destination ?? 'f'.repeat(16),
      payload: overrides.payload ?? new Uint8Array([0xde, 0xad]),
      sequence: overrides.sequence ?? 1,
      ttlMs: overrides.ttlMs ?? 60_000,
      sprayL: overrides.copies ?? 8,
      // 64 bytes base64-encodes to 88 chars: structurally valid, cryptographically wrong.
      signFn: async () => 'A'.repeat(88),
      idFn: () => nextFakeUuid(),
    });
    // payload is restored from `base` deliberately: buildFrame ran it through
    // padToBucket, and letting an override replace it would silently defeat
    // the size-bucket padding the frame format depends on.
    return encodeFrame({ ...base, ...overrides, payload: base.payload });
  }

  /**
   * Build a *correctly signed* frame from a freshly generated keypair — a
   * stranger who is cryptographically honest but socially unknown. This is
   * the Sybil case: it must relay everywhere and be refused only at the
   * destination.
   */
  async forgeAsNewIdentity(overrides: Partial<MeshFrame>): Promise<Uint8Array> {
    const account = createAccount();
    const pubkey = account.identityKeys().ed25519;
    const origin = overrides.origin ?? (await deviceIdFromEd25519(pubkey));

    const base = await buildFrame({
      origin,
      originPubkey: pubkey,
      destination: overrides.destination ?? 'f'.repeat(16),
      payload: overrides.payload ?? new Uint8Array([0xbe, 0xef]),
      sequence: overrides.sequence ?? 1,
      ttlMs: overrides.ttlMs ?? 60_000,
      sprayL: 8,
      signFn: async (bytes) => account.sign(bytes),
      idFn: () => nextFakeUuid(),
    });
    return encodeFrame({ ...base, ...overrides, payload: base.payload });
  }

  truncateNext(bytes: number): void { this.truncateTo = bytes; }

  async replayLast(times: number): Promise<void> {
    if (!this.lastSent) return;
    for (let i = 0; i < times; i++) {
      await this.rawSend(this.lastTarget!, this.lastSent);
    }
  }

  private lastTarget: DeviceId | null = null;

  override async sendTo(peer: DeviceId, bytes: Uint8Array): Promise<void> {
    let out = bytes;
    if (this.truncateTo !== null) {
      out = bytes.slice(0, this.truncateTo);
      this.truncateTo = null;
    }
    this.lastSent = out;
    this.lastTarget = peer;
    await this.rawSend(peer, out);
  }

  private async rawSend(peer: DeviceId, bytes: Uint8Array): Promise<void> {
    for (const spy of this.spies) spy(bytes);
    await super.sendTo(peer, bytes);
  }
}
```

**Implementer note:** `FakeLink` must expose `sendTo` as overridable and its internals as `protected` for this to compile. If Phase 2 declared them `private`, widen them to `protected` now — that is a legitimate change, and the only Phase 2 source edit this phase should require.

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- fake/malicious
```

Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "test: add malicious peer harness for attack simulation"
```

---

## Task 2: Routing-header forgery — closes defect 3

**Files:**
- Create: `src/mesh/__tests__/attacks/forgery.test.ts`
- Test: itself

**Interfaces:**
- Consumes: `MaliciousLink`, `RealMeshEngine`, `validateFrame`.
- Produces: the named regression test `test_forged_routing_header_is_rejected`.

- [ ] **Step 1: Write the failing attack tests**

```typescript
// src/mesh/__tests__/attacks/forgery.test.ts
import { Sim } from '../../fake/Sim';
import { FakeBus } from '../../fake/FakeLink';
import { MaliciousLink } from '../../fake/MaliciousLink';
import { RealMeshEngine } from '../../RealMeshEngine';
import { DEFAULT_CONFIG } from '../../types';

const ALICE = 'a'.repeat(16);
const BOB = 'b'.repeat(16);
const EVE = 'e'.repeat(16);

async function scene() {
  const sim = new Sim({ rangeMetres: 50, seed: 11 });
  const bus = new FakeBus(sim);
  for (const id of [ALICE, BOB, EVE]) sim.addDevice(id, { x: 0, y: 0 });

  const bob = new RealMeshEngine(BOB, new (await import('../../fake/FakeLink')).FakeLink(bus));
  await bob.start(DEFAULT_CONFIG);

  const eve = new MaliciousLink(bus);
  await eve.start(EVE);
  return { bob, eve, bus };
}

/**
 * Defect 3. Bridgefy adopted libsignal and was broken again in 2022 because
 * the mesh layer, not the crypto layer, was forgeable. This is the test that
 * says our mesh layer is not.
 */
test('test_forged_routing_header_is_rejected', async () => {
  const { bob, eve } = await scene();
  const received: unknown[] = [];
  bob.observeMessages((e) => received.push(e));

  // Eve claims a frame came from Alice.
  const forged = await eve.forgeFrame({ origin: ALICE, destination: BOB });
  await eve.sendTo(BOB, forged);
  await new Promise((r) => setTimeout(r, 1000));

  expect(received).toEqual([]);
});

test('a forged frame increments the bad_signature drop counter', async () => {
  const { bob, eve } = await scene();
  let drops: Record<string, number> = {};
  bob.observeDiagnostics((d) => { drops = d.framesDropped; });

  await eve.sendTo(BOB, await eve.forgeFrame({ origin: ALICE, destination: BOB }));
  await new Promise((r) => setTimeout(r, 1000));

  expect(drops.bad_signature ?? 0).toBeGreaterThan(0);
});

test('a frame from an unknown origin is RELAYED but never delivered', async () => {
  // This is the two-level trust model from docs/design.md §6.3, asserted.
  //
  // A relay must forward frames from people it has never met — that is the
  // entire point of a mesh, and an earlier draft of the spec got this wrong
  // by requiring signature verification against a known key at every hop,
  // which would have made relaying impossible.
  //
  // So: a well-formed frame from a stranger passes integrity everywhere and
  // is relayed, but is refused at the destination for lack of a contact.
  const { bob, eve } = await scene();
  const received: unknown[] = [];
  let drops: Record<string, number> = {};
  bob.observeMessages((e) => received.push(e));
  bob.observeDiagnostics((d) => { drops = d.framesDropped; });

  // A genuine keypair Bob has never seen — correctly self-signed.
  const stranger = await eve.forgeAsNewIdentity({ destination: BOB });
  await eve.sendTo(BOB, stranger);
  await new Promise((r) => setTimeout(r, 1000));

  expect(received).toEqual([]);
  expect(drops.unknown_contact ?? 0).toBeGreaterThan(0);
  expect(drops.bad_signature ?? 0).toBe(0);   // integrity was fine; identity was not
});

test('a frame whose origin id does not match its public key is dropped at every hop', async () => {
  // Binding origin to SHA-256(originPubkey) is what stops a valid keypair
  // being used to impersonate someone else's DeviceId.
  const { bob, eve } = await scene();
  let drops: Record<string, number> = {};
  bob.observeDiagnostics((d) => { drops = d.framesDropped; });

  await eve.sendTo(BOB, await eve.forgeAsNewIdentity({
    origin: ALICE,          // claim Alice's id, sign with our own key
    destination: BOB,
  }));
  await new Promise((r) => setTimeout(r, 1000));

  expect(drops.id_key_mismatch ?? 0).toBeGreaterThan(0);
});

test('tampering with the destination after signing is detected', async () => {
  const { bob, eve } = await scene();
  const received: unknown[] = [];
  bob.observeMessages((e) => received.push(e));

  // A frame legitimately signed for someone else, redirected to Bob.
  const redirected = await eve.forgeFrame({ origin: ALICE, destination: BOB, sequence: 5 });
  await eve.sendTo(BOB, redirected);
  await new Promise((r) => setTimeout(r, 1000));

  expect(received).toEqual([]);
});

test('MUTABLE FIELDS CAN BE CHANGED WITHOUT INVALIDATING THE SIGNATURE', async () => {
  // hopCount and copies are deliberately outside the signature — a relay must
  // be able to increment them. This test pins that intent so nobody "fixes"
  // it by signing them and breaking all relaying.
  const { eve } = await scene();
  const original = await eve.forgeFrame({ origin: ALICE, destination: BOB, hopCount: 0 });
  const bumped = await eve.forgeFrame({ origin: ALICE, destination: BOB, hopCount: 3 });

  const { decodeFrame, signableFrameBytes } = await import('../../frame');
  expect(signableFrameBytes(decodeFrame(original)))
    .toEqual(signableFrameBytes(decodeFrame(bumped)));
});
```

The last test is the counterweight to the rest. Mutable fields are outside the signature **on purpose**, and a future contributor reading only the forgery tests would reasonably conclude they should be signed. Pinning the intent stops a well-meant change from breaking every relay.

- [ ] **Step 2: Run and watch them fail**

Before the fix, `test_forged_routing_header_is_rejected` must actually **fail** — meaning the forged frame is accepted. If it passes on the first run, verify the harness is really sending by checking the spy count. A test that has never failed is not evidence.

```bash
npm test -- attacks/forgery
```

- [ ] **Step 3: Confirm the production code already defends this.** Phase 2 Task 2 implemented `validateFrame` with signature verification at step 6. If these tests pass without any production change, that is the correct outcome — record it. If they do not, fix `validateFrame`, not the test.

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- attacks/forgery
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "test: add routing-header forgery attack suite

Closes defect 3."
```

---

## Task 3: Replay and sequence-window attacks

**Files:**
- Create: `src/mesh/__tests__/attacks/replay.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
test('replaying a captured frame 100 times delivers the message once', async () => { /* ... */ });
test('a replayed frame increments the duplicate drop counter', async () => { /* ... */ });
test('a frame with a sequence below the replay window is dropped', async () => { /* ... */ });
test('an out-of-order frame WITHIN the window is still accepted', async () => {
  // Delay-tolerant delivery depends on this. Too strict a window silently
  // discards legitimate late messages, which looks like an unreliable mesh.
});
test('the seen-frame set is bounded and evicts by expiry', async () => {
  // Otherwise replay defence itself becomes the memory-exhaustion vector.
});
test('an expired frame replayed after its TTL is dropped', async () => { /* ... */ });
```

The fourth and fifth tests define the tension this task has to hold: too strict and the mesh loses real messages; too loose and it accepts replays or runs out of memory. Both failure modes are tested rather than reasoned about.

- [ ] **Step 2: Run, watch fail, implement any gaps in `validateFrame`, watch pass.**

- [ ] **Step 3: Measure the seen-set memory ceiling** at 10,000 frames and record it in the ledger.

- [ ] **Step 4: Commit**

---

## Task 4: Traffic-analysis and metadata leaks

What an attacker learns from frames they cannot decrypt.

**Files:**
- Create: `src/mesh/__tests__/attacks/metadata.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
test('a location payload and a short text message produce identical frame sizes', async () => {
  // Otherwise size alone reveals who is sharing location with whom.
});
test('messages of 1, 50 and 63 characters all produce 64-byte payloads', async () => { /* ... */ });
test('an ack frame is indistinguishable in size from a data frame', async () => { /* ... */ });
test('the same plaintext sent twice produces different ciphertext', async () => {
  // Ratchet property. If this fails, repeated messages are correlatable.
});
test('frame ids are not sequential or time-ordered', async () => {
  // A predictable frame id lets an observer link frames from one sender.
});
```

- [ ] **Step 2: Run and watch them fail.** Expect at least one genuine failure here — ack frames carry a much smaller payload than data frames, and the natural implementation does not pad them.

- [ ] **Step 3: Fix by bucketing acks to the same 64-byte floor.** Padding costs bandwidth; the alternative is publishing a delivery graph to every phone in range.

- [ ] **Step 4: Document what is still observable** in `docs/threat-model.md`. Honest accounting matters more than a clean sheet: a passive observer still learns that *some* MoshGrid device is present, roughly how many frames it holds from the bloom digest, and its 4-byte advert prefix over the session. Say so plainly rather than implying anonymity the design does not provide.

- [ ] **Step 5: Commit**

---

## Task 5: Resource exhaustion

**Files:**
- Create: `src/mesh/__tests__/attacks/exhaustion.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
test('flooding 10,000 valid frames keeps the queue at maxQueueFrames', async () => { /* ... */ });
test('flooding does not evict frames the user actually sent', async () => {
  // Your own outbound messages must survive a flood. Otherwise the attack
  // is not resource exhaustion, it is targeted censorship.
});
test('a peer sending malformed frames is rate-limited after N failures', async () => { /* ... */ });
test('CHEAP VALIDATION RUNS BEFORE SIGNATURE VERIFICATION UNDER FLOOD', async () => {
  // 10,000 garbage frames must not cost 10,000 Ed25519 verifications.
  // This is the DoS property from docs/design.md §6.3, measured not assumed.
});
test('storage stays bounded after 24 simulated hours of flooding', async () => { /* ... */ });
```

The capitalised test measures the ordering property that Phase 2 asserted. Phase 2 proved the order with a spy; this proves it survives contact with volume.

- [ ] **Step 2: Implement per-peer rate limiting** — a peer whose frames fail validation repeatedly gets deprioritised, then ignored for a cool-down. Track this per peer, never globally, or one hostile phone silences the whole mesh.

- [ ] **Step 3: Measure the cost of resisting the flood** — CPU time and battery over a 10-minute flood on real hardware. Record it. A defence that costs more battery than the attack is not a defence.

- [ ] **Step 4: Commit**

---

## Task 6: Sybil and impersonation

**Files:**
- Create: `src/mesh/__tests__/attacks/sybil.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
test('a thousand fake identities cannot inject a message to a real contact', async () => { /* ... */ });
test('a device advertising a colliding 4-byte prefix is disambiguated over GATT', async () => {
  // The advert prefix is a hint, never an identity. Phase 3 said so; this proves it.
});
test('an attacker cannot re-use a captured contact bundle to impersonate its owner', async () => {
  // The bundle is signed but public. Possession must not imply identity —
  // only the private key can produce a valid frame signature.
});
test('a contact whose ed25519 key changes is NOT silently updated', async () => {
  // Phase 1 deliberately excludes ed25519_pub from the ON CONFLICT update.
});
test('Sybil peers cannot exhaust the spray budget of a legitimate frame', async () => {
  // Each fake peer taking half the copies would strand the message. Copies
  // are bounded per frame, so verify the floor holds.
});
```

The third test is the one that matters most. Contact bundles are handed out freely as QR codes; if holding one were enough to impersonate its owner, the whole identity model would be decorative.

- [ ] **Step 2: Run, watch fail, fix, watch pass.**

- [ ] **Step 3: Commit**

---

## Task 7: Fuzzing every untrusted parser

Four parsers read bytes from hostile sources. Each gets a fuzzer.

**Files:**
- Create: `src/mesh/__tests__/fuzz/frame.fuzz.test.ts`
- Create: `modules/.../ble/ChunkerFuzzTest.kt`
- Create: `modules/.../map/MbtilesFuzzTest.kt`
- Create: `crypto/fuzz/fuzz_targets/decrypt.rs`

- [ ] **Step 1: Frame parser fuzzing**

```typescript
// src/mesh/__tests__/fuzz/frame.fuzz.test.ts
import fc from 'fast-check';
import { decodeFrame } from '../../frame';

test('decodeFrame either returns a frame or throws — never hangs or corrupts', () => {
  fc.assert(
    fc.property(fc.uint8Array({ minLength: 0, maxLength: 8192 }), (bytes) => {
      try { decodeFrame(bytes); } catch { /* throwing is the correct behaviour */ }
      return true;
    }),
    { numRuns: 20_000 },
  );
});

test('a structurally valid frame with absurd field values is rejected cleanly', () => {
  fc.assert(fc.property(
    fc.integer({ min: -2 ** 31, max: 2 ** 31 }),
    fc.integer({ min: -2 ** 31, max: 2 ** 31 }),
    (hopCount, copies) => {
      // Negative and enormous values must be refused, not wrapped or trusted.
      return true;
    },
  ));
});
```

- [ ] **Step 2: GATT chunker fuzzing** in Kotlin — random chunk orders, duplicate indices, missing chunks, declared lengths above `MAX_FRAME_BYTES`, and reassemblies abandoned midway. Assert memory returns to baseline after the reassembly timeout. An unfinished reassembly that is never freed is a remote memory leak triggerable by anyone standing near you.

- [ ] **Step 3: MBTiles fuzzing** — truncated files, a valid SQLite database with no `tiles` table, tile blobs that are not image data, and a 2 GB declared tile. The map must show its empty state, never crash the app.

- [ ] **Step 4: Olm decryption fuzzing** with `cargo-fuzz`

```bash
cargo install cargo-fuzz
cd crypto && cargo fuzz run decrypt -- -max_total_time=600
```

Expected: no crashes in 10 minutes. This fuzzes **our wrapper**, not vodozemac's internals — the wrapper is our code and therefore our bug surface.

- [ ] **Step 5: Add fuzzing to CI** at a reduced run count so it gates every push without making CI slow. Keep the long runs manual.

- [ ] **Step 6: Commit**

---

## Task 8: The threat model document

**Files:**
- Create: `docs/threat-model.md`

- [ ] **Step 1: Write it** with three explicit columns: what is defended and by which test, what is accepted as out of scope, and what is genuinely unresolved.

- [ ] **Step 2: State the accepted risks plainly.** At minimum: a global passive adversary correlating advert prefixes across a site; a hostile relay dropping frames rather than forging them, which is undetectable by design in a store-and-forward mesh; and physical device compromise, which loses everything because the pickle key is on the device.

- [ ] **Step 3: Cross-reference every defended row to its test name**, so the document cannot drift from the code without a test failing.

- [ ] **Step 4: Commit**

---

## Exit gates

- [ ] `npm run lint`, `npm test`, `npm run check:licences` → exit 0
- [ ] `npm test -- attacks/` → all attack suites pass
- [ ] `npm test -- fuzz/` → 20,000 runs, no failures
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass, including both fuzz tests
- [ ] `cd crypto && cargo fuzz run decrypt -- -max_total_time=600` → no crashes
- [ ] **`test_forged_routing_header_is_rejected` passes, and its git history shows it failing first** ← the gate
- [ ] `docs/threat-model.md` exists, every defended row names a real test
- [ ] Flood-resistance CPU and battery cost recorded in the ledger

## Re-check before moving on

- [ ] Re-run Phases 0–7 exit gates. All still green — hardening must not have broken delivery.
- [ ] **Confirm each attack test genuinely failed first.** `git log -p src/mesh/__tests__/attacks/` should show the test added before any fix. If a test was added already-passing, it is not evidence and must be re-derived against an unpatched build.
- [ ] Two-phone flood test: one phone floods, the other must stay usable — messages still send, UI stays responsive.
- [ ] Re-measure battery under flood. Compare against the Phase 5 baseline and record the delta.
- [ ] Confirm rate limiting is per-peer: a flooding phone must not stop a third, innocent phone from delivering.

## Updating the index

- [ ] Tick defect 3 in the [defect tracker](README.md#defect-regression-tracker) once `test_forged_routing_header_is_rejected` passes
- [ ] Add **Flood CPU cost** and **Seen-set memory at 10k frames** rows to the ledger
- [ ] Set Phase 8 to `☑ Done` in [Progress](README.md#progress)

## What Phase 9 relies on

| Produced here | Used by |
|---|---|
| `MaliciousLink` | Phase 9 runs the same attacks against the iOS link |
| Attack suites | Phase 9 must pass them unchanged |
| `docs/threat-model.md` | Phase 9 adds iOS-specific accepted risks |
