# Phase 2 — Chat Over a Simulated Mesh

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** a complete, working, end-to-end encrypted chat app — multi-hop delivery, store-and-forward, delivery receipts — running entirely on one phone against a simulated mesh. No Bluetooth.

**Architecture:** the `MeshEngine` interface, a `FakeMeshEngine` that simulates a crowd of virtual devices with range, packet loss, latency and churn, and the real Session / Envelope / Routing layers running inside it. Only the Link layer is fake.

**Tech Stack:** TypeScript, vodozemac Olm sessions, fast-check property testing, Jest (jest-expo), Maestro.

**Spec:** [`../docs/design.md`](../docs/design.md) §5, §6.3–§6.6

**Devices:** 1 Android phone. Everything here also runs on your Mac in CI.

**Depends on:** Phase 1 complete.

## Why this phase is the pivot

At the end of this phase the app is **feature-complete for chat**. Every phase after it replaces machinery underneath a UI that does not change.

That has a consequence worth stating plainly: **if any later phase requires editing a file under `src/app/`, the interface was designed wrong.** Stop and fix the interface rather than the screen. This is the check that keeps "UI first, engine alongside" honest instead of aspirational.

It also means routing correctness gets proven here, in milliseconds, on any machine — long before two phones and a Bluetooth stack are in the picture. When Phase 4's real mesh disagrees with this simulator, the disagreement localises the bug immediately.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Enforced directly here:

- **Signed routing headers.** Immutable frame fields covered by the origin's Ed25519 signature.
- **Validation order is fixed:** cheap checks before signature verification.
- **`MAX_HOPS` = 8, `SPRAY_L` default 8, `MAX_FRAME_BYTES` = 4096.**
- **Size buckets:** 64 / 256 / 1024 / 4096.
- **No performance number ships unmeasured.**

## File structure

| File | Responsibility |
|---|---|
| `src/mesh/types.ts` | Shared types: DeviceId, PeerSnapshot, MessageEvent |
| `src/mesh/MeshEngine.ts` | The interface. No implementation |
| `src/mesh/frame.ts` | Envelope: build, encode, decode, sign, validate |
| `src/mesh/routing.ts` | Spray-and-wait decision, queue policy |
| `src/mesh/bloom.ts` | 256-bit filter, two keyed hashes |
| `src/mesh/RealMeshEngine.ts` | Wires Session + Envelope + Routing to a Link |
| `src/mesh/Link.ts` | Link interface — the only thing Phase 3 replaces |
| `src/mesh/fake/FakeLink.ts` | Simulated radio: range, loss, latency, churn |
| `src/mesh/fake/Sim.ts` | Virtual crowd, clock, scenario scripting |
| `src/mesh/index.ts` | Engine selection — the single runtime decision |
| `src/db/messages.ts` | Message CRUD and state transitions |
| `src/app/chat/[deviceId].tsx` | Chat screen |

---

## Task 1: The MeshEngine and Link interfaces

Defining both before either implementation exists is deliberate — it forces the seam to be designed rather than discovered.

**Files:**
- Create: `src/mesh/types.ts`, `src/mesh/MeshEngine.ts`, `src/mesh/Link.ts`
- Test: `src/mesh/__tests__/contract.test.ts`

**Interfaces:**
- Consumes: `Contact` from Phase 1.
- Produces: `MeshEngine`, `Link`, and a reusable contract test suite every implementation must pass.

- [ ] **Step 1: Write the types**

```typescript
// src/mesh/types.ts
export type DeviceId = string;   // 16 lowercase hex chars
export type MessageId = string;  // uuid v4
export type FrameId = string;    // 32 lowercase hex chars

export const MAX_HOPS = 8;
export const SPRAY_L_DEFAULT = 8;
export const MAX_FRAME_BYTES = 4096;
export const SIZE_BUCKETS = [64, 256, 1024, 4096] as const;

export type MessageState = 'queued' | 'sent' | 'delivered' | 'failed';

export type PeerSnapshot = {
  deviceId: DeviceId;
  rssi: number | null;
  lastSeenAt: number;
  hasPendingForMe: boolean;
};

export type MessageEvent =
  | { kind: 'received'; from: DeviceId; messageId: MessageId; body: string; at: number }
  | { kind: 'state'; messageId: MessageId; state: MessageState; at: number };

export type DiagnosticsSnapshot = {
  peersInRange: number;
  queuedFrames: number;
  framesRelayed: number;
  framesDropped: Record<string, number>;  // reason -> count
  bloomFillRatio: number;
};

export type EngineConfig = {
  sprayL: number;
  ttlMs: number;
  maxQueueFrames: number;
};

export const DEFAULT_CONFIG: EngineConfig = {
  sprayL: SPRAY_L_DEFAULT,
  ttlMs: 4 * 60 * 60 * 1000,
  maxQueueFrames: 500,
};
```

- [ ] **Step 2: Write the interfaces**

```typescript
// src/mesh/Link.ts
import type { DeviceId, PeerSnapshot } from './types';

/** The only layer Phase 3 replaces. Everything above it is already real. */
export interface Link {
  start(selfId: DeviceId): Promise<void>;
  stop(): Promise<void>;
  /** Peers currently reachable, refreshed as the radio sees them. */
  onPeersChanged(cb: (peers: PeerSnapshot[]) => void): () => void;
  /** Deliver raw frame bytes to one peer. Resolves when handed to the transport. */
  sendTo(peer: DeviceId, bytes: Uint8Array): Promise<void>;
  /** Raw frame bytes arriving from any peer. */
  onFrame(cb: (from: DeviceId, bytes: Uint8Array) => void): () => void;
  /** Advertised digest of what this peer is carrying, if the transport supports it. */
  onDigest(cb: (from: DeviceId, bloom: Uint8Array) => void): () => void;
  setLocalDigest(bloom: Uint8Array): void;
}
```

```typescript
// src/mesh/MeshEngine.ts
import type {
  DeviceId, MessageId, PeerSnapshot, MessageEvent,
  DiagnosticsSnapshot, EngineConfig,
} from './types';
import type { ContactBundle } from '../crypto/bundle';

export interface MeshEngine {
  start(config: EngineConfig): Promise<void>;
  stop(): Promise<void>;
  sendMessage(to: DeviceId, body: string): Promise<MessageId>;
  addContact(bundle: ContactBundle): Promise<void>;
  observePeers(cb: (peers: PeerSnapshot[]) => void): () => void;
  observeMessages(cb: (event: MessageEvent) => void): () => void;
  observeDiagnostics(cb: (snapshot: DiagnosticsSnapshot) => void): () => void;
}
```

Every observer returns its own unsubscribe function. React effects leak listeners otherwise, and a leaked listener in a mesh engine means messages delivered to unmounted screens.

- [ ] **Step 3: Write the shared contract test**

```typescript
// src/mesh/__tests__/contract.test.ts
import type { MeshEngine } from '../MeshEngine';
import { DEFAULT_CONFIG } from '../types';

/**
 * Every MeshEngine implementation must pass this suite unchanged.
 * Phase 3 runs it against the real engine too.
 */
export function describeMeshEngineContract(
  name: string,
  makeEngine: () => Promise<MeshEngine>,
) {
  describe(`${name} satisfies the MeshEngine contract`, () => {
    test('start then stop is clean', async () => {
      const engine = await makeEngine();
      await engine.start(DEFAULT_CONFIG);
      await expect(engine.stop()).resolves.toBeUndefined();
    });

    test('unsubscribing stops delivery', async () => {
      const engine = await makeEngine();
      await engine.start(DEFAULT_CONFIG);
      let calls = 0;
      const off = engine.observePeers(() => { calls++; });
      off();
      const after = calls;
      await new Promise((r) => setTimeout(r, 50));
      expect(calls).toBe(after);
      await engine.stop();
    });

    test('sendMessage returns a message id', async () => {
      const engine = await makeEngine();
      await engine.start(DEFAULT_CONFIG);
      const id = await engine.sendMessage('a'.repeat(16), 'hello');
      expect(id).toMatch(/^[0-9a-f-]{36}$/);
      await engine.stop();
    });
  });
}
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: define MeshEngine and Link interfaces with a shared contract suite"
```

---

## Task 2: The frame envelope

**Files:**
- Create: `src/mesh/frame.ts`
- Test: `src/mesh/__tests__/frame.test.ts`

**Interfaces:**
- Consumes: `sign`/`verify` from Phase 1's crypto module.
- Produces: `buildFrame`, `encodeFrame`, `decodeFrame`, `validateFrame`, `padToBucket`, and the `MeshFrame` type plus `DropReason`.

- [ ] **Step 1: Write the failing tests**

```typescript
// src/mesh/__tests__/frame.test.ts
import {
  buildFrame, encodeFrame, decodeFrame, validateFrame, padToBucket, signableFrameBytes,
} from '../frame';
import { MAX_HOPS, MAX_FRAME_BYTES } from '../types';

const origin = 'a'.repeat(16);
const dest = 'b'.repeat(16);

async function validFrame() {
  return buildFrame({
    origin, destination: dest, payload: new Uint8Array([1, 2, 3]),
    sequence: 1, ttlMs: 60_000, sprayL: 8,
  });
}

test('encode then decode round-trips every field', async () => {
  const frame = await validFrame();
  expect(decodeFrame(encodeFrame(frame))).toEqual(frame);
});

test('signable bytes exclude the mutable fields', async () => {
  const frame = await validFrame();
  const a = signableFrameBytes(frame);
  const b = signableFrameBytes({ ...frame, hopCount: 5, copies: 2 });
  expect(a).toEqual(b);
});

test('signable bytes change when the destination changes', async () => {
  const frame = await validFrame();
  expect(signableFrameBytes(frame))
    .not.toEqual(signableFrameBytes({ ...frame, destination: 'c'.repeat(16) }));
});

test('rejects a frame past MAX_HOPS', async () => {
  const frame = { ...(await validFrame()), hopCount: MAX_HOPS + 1 };
  expect(await validateFrame(frame, { knownKeys: {}, seen: new Set(), now: Date.now() }))
    .toEqual({ ok: false, reason: 'hop_limit' });
});

test('rejects an expired frame', async () => {
  const frame = await validFrame();
  const later = frame.createdAtMs + frame.ttlMs + 1;
  expect(await validateFrame(frame, { knownKeys: {}, seen: new Set(), now: later }))
    .toEqual({ ok: false, reason: 'expired' });
});

test('rejects a duplicate frame id', async () => {
  const frame = await validFrame();
  const seen = new Set([frame.frameId]);
  expect(await validateFrame(frame, { knownKeys: {}, seen, now: Date.now() }))
    .toEqual({ ok: false, reason: 'duplicate' });
});

test('rejects an oversized frame', async () => {
  const frame = { ...(await validFrame()), payload: new Uint8Array(MAX_FRAME_BYTES + 1) };
  expect(await validateFrame(frame, { knownKeys: {}, seen: new Set(), now: Date.now() }))
    .toEqual({ ok: false, reason: 'too_large' });
});

test('CHEAP CHECKS RUN BEFORE SIGNATURE VERIFICATION', async () => {
  const frame = { ...(await validFrame()), hopCount: MAX_HOPS + 1 };
  let verifyCalls = 0;
  await validateFrame(frame, {
    knownKeys: {}, seen: new Set(), now: Date.now(),
    verifyFn: async () => { verifyCalls++; return true; },
  });
  expect(verifyCalls).toBe(0);
});

test('padToBucket rounds up to the next bucket', () => {
  expect(padToBucket(new Uint8Array(10)).length).toBe(64);
  expect(padToBucket(new Uint8Array(200)).length).toBe(256);
  expect(padToBucket(new Uint8Array(300)).length).toBe(1024);
});
```

The capitalised test is the one that matters for denial-of-service. If signature verification runs before the hop check, any peer can force every device in range to burn CPU on Ed25519 verification of garbage.

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- mesh/frame
```

- [ ] **Step 3: Implement**

```typescript
// src/mesh/frame.ts
import { encode as cborEncode, decode as cborDecode } from 'cbor-x';
import { randomUUID } from 'expo-crypto';
import { verify } from '../../modules/moshgrid-core';
import { MAX_HOPS, MAX_FRAME_BYTES, SIZE_BUCKETS, type DeviceId, type FrameId } from './types';

export const FRAME_VERSION = 1;

export type FrameType = 1 | 2 | 3 | 4;  // data | ack | digest_req | digest_resp

export type MeshFrame = {
  version: number;
  frameType: FrameType;
  frameId: FrameId;
  origin: DeviceId;
  /**
   * The origin's base64 Ed25519 public key, carried inside the signed region.
   *
   * This is what makes verification self-contained. A relay has never met the
   * people whose frames it carries, so it holds no key for them — without this
   * field every relay would drop every stranger's frame and multi-hop delivery
   * would be impossible. See docs/design.md §6.3.
   */
  originPubkey: string;
  destination: DeviceId;
  createdAtMs: number;
  ttlMs: number;
  sequence: number;
  payload: Uint8Array;
  originSig: string;
  // mutable, not signed
  hopCount: number;
  copies: number;
};

export type DropReason =
  | 'bad_version' | 'too_large' | 'hop_limit' | 'copy_limit'
  | 'expired' | 'duplicate' | 'id_key_mismatch' | 'bad_signature'
  | 'unknown_contact' | 'replay';

export type ValidateResult = { ok: true } | { ok: false; reason: DropReason };

export type ValidateContext = {
  /** Contacts we know. Consulted only when `isDestination` is true. */
  knownKeys: Record<DeviceId, string>;   // deviceId -> base64 Ed25519 public key
  seen: Set<FrameId>;
  now: number;
  /** True when this device is the frame's destination. Relays pass false. */
  isDestination: boolean;
  /** Derives a DeviceId from a base64 Ed25519 key. Injected so this is testable. */
  deviceIdFn: (pubkey: string) => Promise<DeviceId>;
  highestSequence?: Record<DeviceId, number>;
  sprayL?: number;
  verifyFn?: (key: string, msg: Uint8Array, sig: string) => Promise<boolean>;
};

export function padToBucket(payload: Uint8Array): Uint8Array {
  const bucket = SIZE_BUCKETS.find((b) => payload.length <= b);
  if (!bucket) throw new Error(`payload of ${payload.length} exceeds the largest bucket`);
  const padded = new Uint8Array(bucket);
  padded.set(payload);
  return padded;
}

/** Exactly the bytes covered by originSig. Mutable fields are excluded by design. */
export function signableFrameBytes(f: MeshFrame): Uint8Array {
  return cborEncode([
    f.version, f.frameType, f.frameId, f.origin, f.originPubkey,
    f.destination, f.createdAtMs, f.ttlMs, f.sequence, f.payload,
  ]);
}

export async function buildFrame(args: {
  origin: DeviceId; destination: DeviceId; payload: Uint8Array;
  sequence: number; ttlMs: number; sprayL: number;
  frameType?: FrameType;
  signFn?: (bytes: Uint8Array) => Promise<string>;
}): Promise<MeshFrame> {
  const frame: MeshFrame = {
    version: FRAME_VERSION,
    frameType: args.frameType ?? 1,
    frameId: randomUUID().replace(/-/g, ''),
    origin: args.origin,
    destination: args.destination,
    createdAtMs: Date.now(),
    ttlMs: args.ttlMs,
    sequence: args.sequence,
    payload: padToBucket(args.payload),
    originSig: '',
    hopCount: 0,
    copies: args.sprayL,
  };
  frame.originSig = args.signFn ? await args.signFn(signableFrameBytes(frame)) : '';
  return frame;
}

export function encodeFrame(f: MeshFrame): Uint8Array {
  return cborEncode([
    f.version, f.frameType, f.frameId, f.origin, f.destination,
    f.createdAtMs, f.ttlMs, f.sequence, f.payload, f.originSig,
    f.hopCount, f.copies,
  ]);
}

export function decodeFrame(bytes: Uint8Array): MeshFrame {
  const p = cborDecode(bytes);
  if (!Array.isArray(p) || p.length !== 12) throw new Error('frame has the wrong shape');
  return {
    version: p[0], frameType: p[1], frameId: p[2], origin: p[3], destination: p[4],
    createdAtMs: p[5], ttlMs: p[6], sequence: p[7], payload: p[8], originSig: p[9],
    hopCount: p[10], copies: p[11],
  };
}

/**
 * Validation order is part of the security design — see docs/design.md §6.3.
 * Cheap checks first so unsigned garbage cannot force Ed25519 work on every peer.
 */
export async function validateFrame(
  f: MeshFrame,
  ctx: ValidateContext,
): Promise<ValidateResult> {
  // --- Steps 1-5: cheap checks, run at EVERY hop. -------------------------
  // These precede any signature work so malformed garbage cannot force
  // Ed25519 verification on every peer in range.
  if (f.version !== FRAME_VERSION) return { ok: false, reason: 'bad_version' };
  if (f.payload.length > MAX_FRAME_BYTES) return { ok: false, reason: 'too_large' };
  if (f.hopCount > MAX_HOPS) return { ok: false, reason: 'hop_limit' };
  if (ctx.sprayL !== undefined && f.copies > ctx.sprayL) return { ok: false, reason: 'copy_limit' };
  if (f.createdAtMs + f.ttlMs <= ctx.now) return { ok: false, reason: 'expired' };
  if (ctx.seen.has(f.frameId)) return { ok: false, reason: 'duplicate' };

  // --- Step 6: INTEGRITY, at every hop. ----------------------------------
  // Self-contained: verified against the key carried in the frame, so a relay
  // needs no prior knowledge of the origin. First bind the short wire id to
  // that key with a cheap hash, so `origin` cannot claim to be someone else.
  const derivedId = await ctx.deviceIdFn(f.originPubkey);
  if (derivedId !== f.origin) return { ok: false, reason: 'id_key_mismatch' };

  const verifyFn = ctx.verifyFn ?? verify;
  if (!(await verifyFn(f.originPubkey, signableFrameBytes(f), f.originSig))) {
    return { ok: false, reason: 'bad_signature' };
  }

  // --- Step 7: AUTHENTICITY, at the DESTINATION only. --------------------
  // "Is this someone I actually know?" is a question only the recipient can
  // answer, and only the recipient needs to. Relays stop at integrity.
  if (!ctx.isDestination) return { ok: true };

  const known = ctx.knownKeys[f.origin];
  if (!known || known !== f.originPubkey) return { ok: false, reason: 'unknown_contact' };

  // Per-origin sequence state is kept only for known contacts. Tracking it for
  // strangers would be exactly the unbounded memory an attacker would target.
  const highest = ctx.highestSequence?.[f.origin];
  if (highest !== undefined && f.sequence <= highest - 1024) {
    return { ok: false, reason: 'replay' };
  }
  return { ok: true };
}
```

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- mesh/frame
```

Expected: 9 passed.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add mesh frame envelope with signed routing header"
```

---

## Task 3: Bloom filter with independent hashes

Closes defect 4.

**Files:**
- Create: `src/mesh/bloom.ts`
- Test: `src/mesh/__tests__/bloom.test.ts`
- Create: `scripts/bench-bloom.mjs`

**Interfaces:**
- Produces: `class BloomFilter` with `add(id)`, `mightContain(id)`, `bytes()`, `fillRatio()`, and `BloomFilter.fromBytes(b)`.

- [ ] **Step 1: Write the failing tests**

```typescript
// src/mesh/__tests__/bloom.test.ts
import { BloomFilter, BLOOM_BITS, hashPair } from '../bloom';

test('a added id is always reported present', () => {
  const f = new BloomFilter();
  f.add('abc123');
  expect(f.mightContain('abc123')).toBe(true);
});

test('serialises to exactly 32 bytes', () => {
  expect(new BloomFilter().bytes().length).toBe(BLOOM_BITS / 8);
});

test('survives a bytes round trip', () => {
  const f = new BloomFilter();
  f.add('xyz');
  expect(BloomFilter.fromBytes(f.bytes()).mightContain('xyz')).toBe(true);
});

/**
 * Defect 4: the source specification used h1 = digest mod 256 and
 * h2 = (digest >> 8) mod 256 — both read the low 16 bits of one value,
 * so they are correlated and the real false-positive rate is worse than
 * its published table.
 */
test('test_bloom_hashes_are_independent', () => {
  const samples = 4000;
  let equal = 0;
  for (let i = 0; i < samples; i++) {
    const [h1, h2] = hashPair(`frame-${i}`);
    if (h1 === h2) equal++;
  }
  // Independent hashes over 256 buckets collide about 1/256 of the time.
  expect(equal / samples).toBeLessThan(0.01);
});

test('false positive rate at 30 entries is close to the theoretical 4.4%', () => {
  const trials = 200;
  let falsePositives = 0;
  for (let t = 0; t < trials; t++) {
    const f = new BloomFilter();
    for (let i = 0; i < 30; i++) f.add(`t${t}-in-${i}`);
    for (let i = 0; i < 50; i++) if (f.mightContain(`t${t}-out-${i}`)) falsePositives++;
  }
  const rate = falsePositives / (trials * 50);
  expect(rate).toBeGreaterThan(0.02);
  expect(rate).toBeLessThan(0.08);
});
```

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- mesh/bloom
```

- [ ] **Step 3: Implement**

```typescript
// src/mesh/bloom.ts
export const BLOOM_BITS = 256;
export const BLOOM_HASHES = 2;

// Two distinct compile-time keys. Distinctness is the whole point — see defect 4.
const KEY_A = 0x9e3779b97f4a7c15n;
const KEY_B = 0xc2b2ae3d27d4eb4fn;

function fnv1a64(input: string, seed: bigint): bigint {
  let hash = seed;
  const prime = 0x100000001b3n;
  const mask = 0xffffffffffffffffn;
  for (let i = 0; i < input.length; i++) {
    hash = (hash ^ BigInt(input.charCodeAt(i))) & mask;
    hash = (hash * prime) & mask;
  }
  // Final avalanche so adjacent inputs do not land in adjacent buckets.
  hash = (hash ^ (hash >> 33n)) & mask;
  hash = (hash * 0xff51afd7ed558ccdn) & mask;
  hash = (hash ^ (hash >> 33n)) & mask;
  return hash;
}

/** Two independently keyed hashes. Never two slices of one value. */
export function hashPair(id: string): [number, number] {
  return [
    Number(fnv1a64(id, KEY_A) % BigInt(BLOOM_BITS)),
    Number(fnv1a64(id, KEY_B) % BigInt(BLOOM_BITS)),
  ];
}

export class BloomFilter {
  private readonly bits: Uint8Array;

  constructor(bits?: Uint8Array) {
    this.bits = bits ?? new Uint8Array(BLOOM_BITS / 8);
  }

  static fromBytes(bytes: Uint8Array): BloomFilter {
    if (bytes.length !== BLOOM_BITS / 8) {
      throw new Error(`bloom filter must be ${BLOOM_BITS / 8} bytes, got ${bytes.length}`);
    }
    return new BloomFilter(new Uint8Array(bytes));
  }

  add(id: string): void {
    for (const bit of hashPair(id)) this.bits[bit >> 3] |= 1 << (bit & 7);
  }

  mightContain(id: string): boolean {
    return hashPair(id).every((bit) => (this.bits[bit >> 3] & (1 << (bit & 7))) !== 0);
  }

  bytes(): Uint8Array { return new Uint8Array(this.bits); }

  fillRatio(): number {
    let set = 0;
    for (const byte of this.bits) set += byte.toString(2).split('1').length - 1;
    return set / BLOOM_BITS;
  }
}
```

**Implementer note:** FNV-1a with an avalanche step is used because it needs no dependency and the independence test proves it is good enough here. If the independence or false-positive test fails, swap in SipHash-2-4 with two distinct keys rather than tuning constants — the test is the specification, not the algorithm.

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- mesh/bloom
```

- [ ] **Step 5: Write the benchmark that fills the ledger**

```javascript
// scripts/bench-bloom.mjs
import { BloomFilter } from '../src/mesh/bloom.ts';

const TRIALS = 2000;
console.log('entries\tmeasured\ttheoretical');
for (const n of [10, 30, 50, 100]) {
  let fp = 0;
  for (let t = 0; t < TRIALS; t++) {
    const f = new BloomFilter();
    for (let i = 0; i < n; i++) f.add(`t${t}-in-${i}`);
    for (let i = 0; i < 20; i++) if (f.mightContain(`t${t}-out-${i}`)) fp++;
  }
  const measured = fp / (TRIALS * 20);
  const theoretical = Math.pow(1 - Math.exp((-2 * n) / 256), 2);
  console.log(`${n}\t${(measured * 100).toFixed(2)}%\t${(theoretical * 100).toFixed(2)}%`);
}
```

Add `"bench:bloom": "node --experimental-strip-types scripts/bench-bloom.mjs"` to `package.json`.

```bash
npm run bench:bloom
```

- [ ] **Step 6: Record the numbers**

Paste the measured values into the [measured-numbers ledger](README.md#measured-numbers-ledger). This is the first row that gets filled. Never fill a row from an estimate.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: add bloom filter with two independently keyed hashes

Closes defect 4."
```

---

## Task 4: Spray-and-wait routing

Closes defect 8.

**Files:**
- Create: `src/mesh/routing.ts`
- Test: `src/mesh/__tests__/routing.test.ts`

**Interfaces:**
- Produces: `sprayDecision(copiesHeld, peerId, destinationId): number`, `class FrameQueue` with `enqueue`, `evictExpired`, `framesFor(peer)`, `size()`.

- [ ] **Step 1: Write the failing tests**

```typescript
// src/mesh/__tests__/routing.test.ts
import fc from 'fast-check';
import { sprayDecision, FrameQueue } from '../routing';

const DEST = 'd'.repeat(16);
const PEER = 'p'.repeat(16);

test('hands over every copy to the destination itself', () => {
  expect(sprayDecision(8, DEST, DEST)).toBe(8);
  expect(sprayDecision(1, DEST, DEST)).toBe(1);
});

test('holds the last copy in the wait phase', () => {
  expect(sprayDecision(1, PEER, DEST)).toBe(0);
});

test('splits copies in half when spraying', () => {
  expect(sprayDecision(8, PEER, DEST)).toBe(4);
  expect(sprayDecision(5, PEER, DEST)).toBe(2);
});

test('test_spray_l_is_configurable', () => {
  // Defect 8: L is a free parameter, not the constant 32 the PDF asserted.
  for (const L of [1, 2, 4, 8, 16, 32]) {
    expect(sprayDecision(L, PEER, DEST)).toBe(Math.floor(L / 2));
  }
});

test('property: a relay never gives away more copies than it holds', () => {
  fc.assert(fc.property(
    fc.integer({ min: 1, max: 255 }),
    fc.hexaString({ minLength: 16, maxLength: 16 }),
    (copies, peer) => {
      const given = sprayDecision(copies, peer, DEST);
      return given <= copies && given >= 0;
    },
  ));
});

test('property: copies are conserved across a handoff', () => {
  fc.assert(fc.property(fc.integer({ min: 2, max: 255 }), (copies) => {
    const given = sprayDecision(copies, PEER, DEST);
    const kept = copies - given;
    return given + kept === copies && kept >= 1;
  }));
});

test('queue evicts expired frames', () => {
  const q = new FrameQueue(10);
  q.enqueue({ frameId: 'a', bytes: new Uint8Array(1), copies: 4, expiresAt: 100 });
  q.enqueue({ frameId: 'b', bytes: new Uint8Array(1), copies: 4, expiresAt: 900 });
  q.evictExpired(500);
  expect(q.size()).toBe(1);
});

test('queue drops the soonest-to-expire frame when full', () => {
  const q = new FrameQueue(2);
  q.enqueue({ frameId: 'a', bytes: new Uint8Array(1), copies: 1, expiresAt: 100 });
  q.enqueue({ frameId: 'b', bytes: new Uint8Array(1), copies: 1, expiresAt: 900 });
  q.enqueue({ frameId: 'c', bytes: new Uint8Array(1), copies: 1, expiresAt: 500 });
  expect(q.size()).toBe(2);
  expect(q.framesFor(PEER).map((f) => f.frameId).sort()).toEqual(['b', 'c']);
});
```

The two `property:` tests encode the invariants that make spray-and-wait bounded. If either can be violated, the mesh can amplify traffic without limit — which is the storage-exhaustion failure mode the PDF's own table attributes to epidemic flooding.

- [ ] **Step 2: Run and watch them fail**

```bash
npm i -D fast-check
npm test -- mesh/routing
```

- [ ] **Step 3: Implement**

```typescript
// src/mesh/routing.ts
import type { DeviceId, FrameId } from './types';

export type QueuedFrame = {
  frameId: FrameId;
  bytes: Uint8Array;
  copies: number;
  expiresAt: number;
};

/**
 * Binary spray-and-wait. L is a free parameter (Spyropoulos et al.), not the
 * constant the source specification asserted — see defect 8. Phase 4 sweeps it.
 */
export function sprayDecision(
  copiesHeld: number,
  peerId: DeviceId,
  destinationId: DeviceId,
): number {
  if (peerId === destinationId) return copiesHeld;
  if (copiesHeld <= 1) return 0;
  return Math.floor(copiesHeld / 2);
}

export class FrameQueue {
  private frames = new Map<FrameId, QueuedFrame>();

  constructor(private readonly maxFrames: number) {}

  enqueue(frame: QueuedFrame): void {
    this.frames.set(frame.frameId, frame);
    while (this.frames.size > this.maxFrames) {
      let soonest: QueuedFrame | null = null;
      for (const f of this.frames.values()) {
        if (!soonest || f.expiresAt < soonest.expiresAt) soonest = f;
      }
      if (soonest) this.frames.delete(soonest.frameId);
    }
  }

  evictExpired(now: number): number {
    let removed = 0;
    for (const [id, f] of this.frames) {
      if (f.expiresAt <= now) { this.frames.delete(id); removed++; }
    }
    return removed;
  }

  framesFor(_peer: DeviceId): QueuedFrame[] {
    return [...this.frames.values()];
  }

  take(frameId: FrameId): QueuedFrame | undefined {
    return this.frames.get(frameId);
  }

  setCopies(frameId: FrameId, copies: number): void {
    const f = this.frames.get(frameId);
    if (!f) return;
    if (copies <= 0) this.frames.delete(frameId);
    else this.frames.set(frameId, { ...f, copies });
  }

  size(): number { return this.frames.size; }
}
```

Eviction drops the **soonest to expire**, not the oldest inserted. An old frame with hours of TTL left is more valuable than a fresh one about to die.

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- mesh/routing
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add binary spray-and-wait routing with a bounded queue

Closes defect 8."
```

---

## Task 5: Olm sessions

**Files:**
- Modify: `crypto/src/lib.rs`, `crypto/src/moshgrid_crypto.udl`
- Create: `src/crypto/session.ts`
- Test: `crypto/tests/session.rs`, `src/crypto/__tests__/session.test.ts`

**Interfaces:**
- Produces: Rust `MoshgridSession` with `encrypt(plaintext) -> OlmMessage`, `decrypt(msg) -> Vec<u8>`, `pickle`/`unpickle`; account methods `create_outbound_session(identity_key, one_time_key)` and `create_inbound_session(identity_key, pre_key_message)`.
- Produces TS: `encryptFor(deviceId, plaintext)`, `decryptFrom(deviceId, ciphertext)`.

- [ ] **Step 1: Write the failing Rust tests**

```rust
// crypto/tests/session.rs
use moshgrid_crypto::MoshgridAccount;

#[test]
fn alice_and_bob_exchange_a_message() {
    let alice = MoshgridAccount::new();
    let bob = MoshgridAccount::new();

    let bob_keys = bob.identity_keys();
    let bob_otk = bob.one_time_keys(1).remove(0);

    let alice_session = alice
        .create_outbound_session(bob_keys.curve25519.clone(), bob_otk)
        .expect("outbound session");

    let msg = alice_session.encrypt(b"see you at the main stage".to_vec());

    let alice_keys = alice.identity_keys();
    let bob_session = bob
        .create_inbound_session(alice_keys.curve25519, msg.clone())
        .expect("inbound session");

    assert_eq!(bob_session.plaintext, b"see you at the main stage".to_vec());
}

#[test]
fn ratchet_survives_out_of_order_delivery() {
    let alice = MoshgridAccount::new();
    let bob = MoshgridAccount::new();
    let bob_otk = bob.one_time_keys(1).remove(0);

    let session = alice
        .create_outbound_session(bob.identity_keys().curve25519, bob_otk)
        .unwrap();

    let first = session.encrypt(b"one".to_vec());
    let second = session.encrypt(b"two".to_vec());

    // Bob receives them in the wrong order — mandatory for a delay-tolerant mesh.
    let mut bob_session = bob
        .create_inbound_session(alice.identity_keys().curve25519, first.clone())
        .unwrap()
        .session;

    assert_eq!(bob_session.decrypt(second).unwrap(), b"two".to_vec());
}

#[test]
fn a_tampered_ciphertext_fails_to_decrypt() {
    let alice = MoshgridAccount::new();
    let bob = MoshgridAccount::new();
    let bob_otk = bob.one_time_keys(1).remove(0);

    let session = alice
        .create_outbound_session(bob.identity_keys().curve25519, bob_otk)
        .unwrap();

    let mut msg = session.encrypt(b"original".to_vec());
    msg.ciphertext.push('X');

    assert!(bob
        .create_inbound_session(alice.identity_keys().curve25519, msg)
        .is_err());
}
```

`ratchet_survives_out_of_order_delivery` is the test that justifies choosing Olm over MLS. If it ever fails, the transport assumption is broken.

- [ ] **Step 2: Run and watch them fail**

```bash
cd crypto && cargo test --test session
```

- [ ] **Step 3: Implement**

Add to `crypto/src/lib.rs` a `MoshgridSession` wrapping `vodozemac::olm::Session`, plus `create_outbound_session` and `create_inbound_session` on `MoshgridAccount`. Both take base64 keys and return the UniFFI-friendly shapes declared in the `.udl`.

```
// additions to crypto/src/moshgrid_crypto.udl
dictionary OlmMessage {
    u32 message_type;   // 0 = pre-key, 1 = normal
    string ciphertext;
};

dictionary InboundResult {
    MoshgridSession session;
    sequence<u8> plaintext;
};

interface MoshgridSession {
    [Name=unpickle, Throws=CryptoError]
    constructor(string pickle, sequence<u8> key);

    OlmMessage encrypt(sequence<u8> plaintext);
    [Throws=CryptoError] sequence<u8> decrypt(OlmMessage message);
    string session_id();
    string pickle(sequence<u8> key);
};
```

Call `check_contributory` on the derived shared secret inside `create_outbound_session` and `create_inbound_session`, before the session is returned. That is where the Phase 1 guard actually earns its place — a guard nothing calls is decoration.

- [ ] **Step 4: Run and watch them pass**

```bash
cd crypto && cargo test && ./../scripts/build-crypto-android.sh
```

- [ ] **Step 5: Add the TypeScript session store**

```typescript
// src/crypto/session.ts
import { getDb } from '../db/client';
import { getContact } from '../db/contacts';
import { pickleKey, unpickleSession, accountCreateOutbound, accountCreateInbound } from '../../modules/moshgrid-core';
import type { DeviceId } from '../mesh/types';

export type OlmMessage = { messageType: number; ciphertext: string };

async function loadSession(deviceId: DeviceId) {
  const db = await getDb();
  const row = db.getFirstSync<{ pickle: string }>(
    'SELECT pickle FROM sessions WHERE device_id = ?', deviceId,
  );
  return row ? unpickleSession(row.pickle, await pickleKey()) : null;
}

async function saveSession(deviceId: DeviceId, session: { pickle: (k: Uint8Array) => string }) {
  const db = await getDb();
  db.runSync(
    `INSERT INTO sessions (device_id, pickle, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET pickle = excluded.pickle, updated_at = excluded.updated_at`,
    deviceId, session.pickle(await pickleKey()), Date.now(),
  );
}

export async function encryptFor(deviceId: DeviceId, plaintext: Uint8Array): Promise<OlmMessage> {
  let session = await loadSession(deviceId);
  if (!session) {
    const contact = await getContact(deviceId);
    if (!contact) throw new Error(`no contact for ${deviceId}`);
    if (!contact.oneTimeKey) throw new Error(`no pre-key for ${deviceId}; rescan their code`);
    session = accountCreateOutbound(contact.curve25519Pub, contact.oneTimeKey);
  }
  const message = session.encrypt(plaintext);
  await saveSession(deviceId, session);
  return message;
}

export async function decryptFrom(deviceId: DeviceId, message: OlmMessage): Promise<Uint8Array> {
  let session = await loadSession(deviceId);
  if (!session) {
    const contact = await getContact(deviceId);
    if (!contact) throw new Error(`no contact for ${deviceId}`);
    const result = accountCreateInbound(contact.curve25519Pub, message);
    await saveSession(deviceId, result.session);
    return result.plaintext;
  }
  const plaintext = session.decrypt(message);
  await saveSession(deviceId, session);
  return plaintext;
}
```

**Schema addition:** `contacts` needs a `one_time_key` column. Add migration v2 to `src/db/schema.ts` — append, never edit v1:

```typescript
// v2 — Phase 2
`ALTER TABLE contacts ADD COLUMN one_time_key TEXT;`,
```

Update `addContactFromBundle` to store `bundle.oneTimeKey`, and `getContact` to select it.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add olm sessions with out-of-order delivery support"
```

---

## Task 6: FakeLink — the simulated crowd

**Files:**
- Create: `src/mesh/fake/Sim.ts`, `src/mesh/fake/FakeLink.ts`
- Test: `src/mesh/fake/__tests__/sim.test.ts`

**Interfaces:**
- Consumes: `Link` from Task 1.
- Produces: `class Sim` (virtual world) and `class FakeLink implements Link`. Phase 4 reuses `Sim` to cross-check the real mesh.

- [ ] **Step 1: Write the failing tests**

```typescript
// src/mesh/fake/__tests__/sim.test.ts
import { Sim } from '../Sim';

test('devices within range see each other', () => {
  const sim = new Sim({ rangeMetres: 50 });
  sim.addDevice('aaaaaaaaaaaaaaaa', { x: 0, y: 0 });
  sim.addDevice('bbbbbbbbbbbbbbbb', { x: 30, y: 0 });
  expect(sim.peersOf('aaaaaaaaaaaaaaaa')).toContain('bbbbbbbbbbbbbbbb');
});

test('devices beyond range do not', () => {
  const sim = new Sim({ rangeMetres: 50 });
  sim.addDevice('aaaaaaaaaaaaaaaa', { x: 0, y: 0 });
  sim.addDevice('bbbbbbbbbbbbbbbb', { x: 120, y: 0 });
  expect(sim.peersOf('aaaaaaaaaaaaaaaa')).toEqual([]);
});

test('rssi falls off with distance', () => {
  const sim = new Sim({ rangeMetres: 100 });
  sim.addDevice('aaaaaaaaaaaaaaaa', { x: 0, y: 0 });
  sim.addDevice('bbbbbbbbbbbbbbbb', { x: 10, y: 0 });
  sim.addDevice('cccccccccccccccc', { x: 80, y: 0 });
  expect(sim.rssiBetween('aaaaaaaaaaaaaaaa', 'bbbbbbbbbbbbbbbb'))
    .toBeGreaterThan(sim.rssiBetween('aaaaaaaaaaaaaaaa', 'cccccccccccccccc'));
});

test('packet loss drops roughly the configured fraction', () => {
  const sim = new Sim({ rangeMetres: 100, lossRate: 0.5, seed: 42 });
  let delivered = 0;
  for (let i = 0; i < 1000; i++) if (sim.rollDelivery()) delivered++;
  expect(delivered).toBeGreaterThan(400);
  expect(delivered).toBeLessThan(600);
});

test('the simulation is deterministic for a given seed', () => {
  const run = () => {
    const sim = new Sim({ rangeMetres: 100, lossRate: 0.3, seed: 7 });
    return Array.from({ length: 50 }, () => sim.rollDelivery());
  };
  expect(run()).toEqual(run());
});
```

Determinism is non-negotiable. A flaky mesh test that cannot be replayed is worse than no test, because it trains you to ignore failures.

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- fake/sim
```

- [ ] **Step 3: Implement**

```typescript
// src/mesh/fake/Sim.ts
import type { DeviceId } from '../types';

export type Point = { x: number; y: number };

export type SimOptions = {
  rangeMetres: number;
  lossRate?: number;
  latencyMs?: [number, number];
  seed?: number;
};

/** Deterministic PRNG so every failure can be replayed exactly. */
function mulberry32(seed: number) {
  return function () {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class Sim {
  private positions = new Map<DeviceId, Point>();
  private random: () => number;

  constructor(private readonly options: SimOptions) {
    this.random = mulberry32(options.seed ?? 1);
  }

  addDevice(id: DeviceId, at: Point): void { this.positions.set(id, at); }
  moveDevice(id: DeviceId, to: Point): void { this.positions.set(id, to); }
  removeDevice(id: DeviceId): void { this.positions.delete(id); }
  devices(): DeviceId[] { return [...this.positions.keys()]; }

  private distance(a: DeviceId, b: DeviceId): number {
    const pa = this.positions.get(a), pb = this.positions.get(b);
    if (!pa || !pb) return Infinity;
    return Math.hypot(pa.x - pb.x, pa.y - pb.y);
  }

  peersOf(id: DeviceId): DeviceId[] {
    return this.devices().filter(
      (other) => other !== id && this.distance(id, other) <= this.options.rangeMetres,
    );
  }

  /** Log-distance path loss, clamped to the range real BLE reports. */
  rssiBetween(a: DeviceId, b: DeviceId): number {
    const d = Math.max(this.distance(a, b), 1);
    return Math.round(Math.max(-100, Math.min(-30, -40 - 25 * Math.log10(d))));
  }

  rollDelivery(): boolean { return this.random() >= (this.options.lossRate ?? 0); }

  rollLatencyMs(): number {
    const [lo, hi] = this.options.latencyMs ?? [50, 400];
    return Math.round(lo + this.random() * (hi - lo));
  }
}
```

```typescript
// src/mesh/fake/FakeLink.ts
import type { Link } from '../Link';
import type { DeviceId, PeerSnapshot } from '../types';
import type { Sim } from './Sim';

type FrameCb = (from: DeviceId, bytes: Uint8Array) => void;

/** One shared bus so every FakeLink in a test talks to the same virtual world. */
export class FakeBus {
  private links = new Map<DeviceId, FakeLink>();
  constructor(readonly sim: Sim) {}
  register(id: DeviceId, link: FakeLink) { this.links.set(id, link); }
  unregister(id: DeviceId) { this.links.delete(id); }
  get(id: DeviceId) { return this.links.get(id); }
}

export class FakeLink implements Link {
  private selfId: DeviceId = '';
  private frameCbs: FrameCb[] = [];
  private peerCbs: ((peers: PeerSnapshot[]) => void)[] = [];
  private digestCbs: ((from: DeviceId, bloom: Uint8Array) => void)[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;
  private localDigest = new Uint8Array(32);

  constructor(private readonly bus: FakeBus) {}

  async start(selfId: DeviceId): Promise<void> {
    this.selfId = selfId;
    this.bus.register(selfId, this);
    this.timer = setInterval(() => this.emitPeers(), 200);
  }

  async stop(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    this.bus.unregister(this.selfId);
  }

  private emitPeers(): void {
    const peers: PeerSnapshot[] = this.bus.sim.peersOf(this.selfId).map((id) => ({
      deviceId: id,
      rssi: this.bus.sim.rssiBetween(this.selfId, id),
      lastSeenAt: Date.now(),
      hasPendingForMe: false,
    }));
    for (const cb of this.peerCbs) cb(peers);
  }

  onPeersChanged(cb: (peers: PeerSnapshot[]) => void): () => void {
    this.peerCbs.push(cb);
    return () => { this.peerCbs = this.peerCbs.filter((c) => c !== cb); };
  }

  onFrame(cb: FrameCb): () => void {
    this.frameCbs.push(cb);
    return () => { this.frameCbs = this.frameCbs.filter((c) => c !== cb); };
  }

  onDigest(cb: (from: DeviceId, bloom: Uint8Array) => void): () => void {
    this.digestCbs.push(cb);
    return () => { this.digestCbs = this.digestCbs.filter((c) => c !== cb); };
  }

  setLocalDigest(bloom: Uint8Array): void { this.localDigest = bloom; }

  async sendTo(peer: DeviceId, bytes: Uint8Array): Promise<void> {
    if (!this.bus.sim.peersOf(this.selfId).includes(peer)) return;   // out of range
    if (!this.bus.sim.rollDelivery()) return;                        // dropped

    const target = this.bus.get(peer);
    if (!target) return;

    const latency = this.bus.sim.rollLatencyMs();
    setTimeout(() => {
      for (const cb of target.frameCbs) cb(this.selfId, bytes);
      for (const cb of target.digestCbs) cb(this.selfId, this.localDigest);
    }, latency);
  }
}
```

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- fake/sim
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add deterministic mesh simulator and fake link"
```

---

## Task 7: RealMeshEngine over the fake link

The engine here is **real** — only the Link is simulated. Phase 3 swaps the Link and nothing else.

**Files:**
- Create: `src/mesh/RealMeshEngine.ts`, `src/mesh/index.ts`, `src/db/messages.ts`
- Test: `src/mesh/__tests__/engine.test.ts`

**Interfaces:**
- Consumes: everything above.
- Produces: `class RealMeshEngine implements MeshEngine`, and `getEngine()` — the single place the implementation is chosen.

- [ ] **Step 1: Write the failing integration tests**

```typescript
// src/mesh/__tests__/engine.test.ts
import { Sim } from '../fake/Sim';
import { FakeBus, FakeLink } from '../fake/FakeLink';
import { RealMeshEngine } from '../RealMeshEngine';
import { DEFAULT_CONFIG } from '../types';

async function crowd(positions: Record<string, { x: number; y: number }>, opts = {}) {
  const sim = new Sim({ rangeMetres: 50, seed: 1, ...opts });
  const bus = new FakeBus(sim);
  const engines: Record<string, RealMeshEngine> = {};
  for (const [id, at] of Object.entries(positions)) {
    sim.addDevice(id, at);
    engines[id] = new RealMeshEngine(id, new FakeLink(bus));
    await engines[id].start(DEFAULT_CONFIG);
  }
  return { sim, engines };
}

const A = 'a'.repeat(16), B = 'b'.repeat(16), C = 'c'.repeat(16);

test('delivers a message between two devices in range', async () => {
  const { engines } = await crowd({ [A]: { x: 0, y: 0 }, [B]: { x: 20, y: 0 } });
  const received: string[] = [];
  engines[B].observeMessages((e) => { if (e.kind === 'received') received.push(e.body); });

  await engines[A].sendMessage(B, 'meet at the bar');
  await new Promise((r) => setTimeout(r, 1500));

  expect(received).toContain('meet at the bar');
});

test('RELAYS THROUGH A MIDDLE DEVICE that is out of direct range', async () => {
  // A -- 40m -- C -- 40m -- B.  A and B are 80m apart, beyond the 50m range.
  const { engines } = await crowd({
    [A]: { x: 0, y: 0 }, [C]: { x: 40, y: 0 }, [B]: { x: 80, y: 0 },
  });
  const received: string[] = [];
  engines[B].observeMessages((e) => { if (e.kind === 'received') received.push(e.body); });

  await engines[A].sendMessage(B, 'relayed through the crowd');
  await new Promise((r) => setTimeout(r, 3000));

  expect(received).toContain('relayed through the crowd');
});

test('STORE AND FORWARD: delivers after the recipient comes into range', async () => {
  const { sim, engines } = await crowd({ [A]: { x: 0, y: 0 }, [B]: { x: 500, y: 0 } });
  const received: string[] = [];
  engines[B].observeMessages((e) => { if (e.kind === 'received') received.push(e.body); });

  await engines[A].sendMessage(B, 'queued while you were away');
  await new Promise((r) => setTimeout(r, 500));
  expect(received).toHaveLength(0);

  sim.moveDevice(B, { x: 20, y: 0 });   // B walks back into range
  await new Promise((r) => setTimeout(r, 2000));

  expect(received).toContain('queued while you were away');
});

test('a relay cannot read what it forwards', async () => {
  const { engines } = await crowd({
    [A]: { x: 0, y: 0 }, [C]: { x: 40, y: 0 }, [B]: { x: 80, y: 0 },
  });
  const seenByRelay: string[] = [];
  engines[C].observeMessages((e) => { if (e.kind === 'received') seenByRelay.push(e.body); });

  await engines[A].sendMessage(B, 'private');
  await new Promise((r) => setTimeout(r, 3000));

  expect(seenByRelay).toEqual([]);
});

test('message state advances from queued to delivered', async () => {
  const { engines } = await crowd({ [A]: { x: 0, y: 0 }, [B]: { x: 20, y: 0 } });
  const states: string[] = [];
  engines[A].observeMessages((e) => { if (e.kind === 'state') states.push(e.state); });

  await engines[A].sendMessage(B, 'track me');
  await new Promise((r) => setTimeout(r, 2000));

  expect(states[0]).toBe('queued');
  expect(states).toContain('delivered');
});
```

The two capitalised tests are the phase's real deliverable. `a relay cannot read what it forwards` is the structural privacy property from §5.1 asserted as a test rather than a claim.

- [ ] **Step 2: Run and watch them fail**

```bash
npm test -- mesh/engine
```

- [ ] **Step 3: Implement `RealMeshEngine`**

Wire the pieces in this order inside the class:

1. `start()` — load identity, subscribe to `link.onFrame` and `link.onPeersChanged`, start a sync ticker.
2. `sendMessage()` — insert the message row as `queued`, `encryptFor()` the body, `buildFrame()` with the signature, enqueue it, emit a `state` event.
3. On tick — for each peer in range, for each queued frame, apply `sprayDecision`, send, and decrement copies via `queue.setCopies`.
4. On frame received — `decodeFrame`, `validateFrame`; on failure increment the drop counter for that reason and stop. On success add to `seen`, then:
   - if `destination === selfId`, `decryptFrom()` and emit `received`, then send an ack frame;
   - otherwise enqueue for relay with `hopCount + 1`.
5. On ack received — update the message row to `delivered`, emit a `state` event.
6. `observeDiagnostics()` — emit `DiagnosticsSnapshot` on every tick.

```typescript
// src/mesh/index.ts
import { RealMeshEngine } from './RealMeshEngine';
import { FakeBus, FakeLink } from './fake/FakeLink';
import { Sim } from './fake/Sim';
import type { MeshEngine } from './MeshEngine';

/** The single place the implementation is chosen. Phase 3 edits this line only. */
export async function getEngine(selfId: string): Promise<MeshEngine> {
  if (process.env.EXPO_PUBLIC_MESH === 'fake') {
    const sim = new Sim({ rangeMetres: 50, seed: 1 });
    sim.addDevice(selfId, { x: 0, y: 0 });
    return new RealMeshEngine(selfId, new FakeLink(new FakeBus(sim)));
  }
  const { BleLink } = await import('./ble/BleLink');   // added in Phase 3
  return new RealMeshEngine(selfId, new BleLink());
}
```

- [ ] **Step 4: Run and watch them pass**

```bash
npm test -- mesh/engine
```

Expected: 5 passed, including relay and store-and-forward.

- [ ] **Step 5: Run the shared contract suite against it**

```typescript
// append to src/mesh/__tests__/engine.test.ts
import { describeMeshEngineContract } from './contract.test';

describeMeshEngineContract('RealMeshEngine over FakeLink', async () => {
  const sim = new Sim({ rangeMetres: 50, seed: 1 });
  sim.addDevice(A, { x: 0, y: 0 });
  return new RealMeshEngine(A, new FakeLink(new FakeBus(sim)));
});
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add RealMeshEngine with relay, store-and-forward, and delivery receipts"
```

---

## Task 8: Chat UI

**Files:**
- Create: `src/app/chat/[deviceId].tsx`, `src/hooks/useEngine.ts`
- Modify: `src/app/contacts.tsx` (rows become links), `src/app/diagnostics.tsx` (add mesh rows)
- Test: `.maestro/send-message.yaml`

**Interfaces:**
- Consumes: `getEngine()`, `MeshEngine`.
- Produces: route `/chat/[deviceId]`. Phases 3–8 do not modify it.

- [ ] **Step 1: Engine provider hook**

```typescript
// src/hooks/useEngine.ts
import { createContext, useContext } from 'react';
import type { MeshEngine } from '../mesh/MeshEngine';

export const EngineContext = createContext<MeshEngine | null>(null);

export function useEngine(): MeshEngine {
  const engine = useContext(EngineContext);
  if (!engine) throw new Error('useEngine must be used inside EngineProvider');
  return engine;
}
```

Mount the provider in `src/app/_layout.tsx`, constructing the engine once via `getEngine()`.

- [ ] **Step 2: Chat screen**

```tsx
// src/app/chat/[deviceId].tsx
import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useEngine } from '../../hooks/useEngine';
import { listMessages, type StoredMessage } from '../../db/messages';

const STATE_MARK: Record<string, string> = {
  queued: '·', sent: '✓', delivered: '✓✓', failed: '!',
};

export default function ChatScreen() {
  const { deviceId } = useLocalSearchParams<{ deviceId: string }>();
  const engine = useEngine();
  const [messages, setMessages] = useState<StoredMessage[]>([]);
  const [draft, setDraft] = useState('');

  useEffect(() => {
    listMessages(deviceId).then(setMessages);
    return engine.observeMessages(() => { listMessages(deviceId).then(setMessages); });
  }, [deviceId, engine]);

  async function send() {
    const body = draft.trim();
    if (!body) return;
    setDraft('');
    await engine.sendMessage(deviceId, body);
  }

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <FlatList
        data={messages}
        inverted
        keyExtractor={(m) => m.id}
        contentContainerStyle={styles.list}
        renderItem={({ item }) => (
          <View style={[styles.bubble, item.direction === 'out' ? styles.mine : styles.theirs]}>
            <Text style={styles.body}>{item.body}</Text>
            {item.direction === 'out' && (
              <Text style={styles.state} testID={`state-${item.id}`}>
                {STATE_MARK[item.state]}
              </Text>
            )}
          </View>
        )}
      />
      <View style={styles.composer}>
        <TextInput
          style={styles.input}
          value={draft}
          onChangeText={setDraft}
          placeholder="Message"
          testID="composer"
          onSubmitEditing={send}
        />
        <Pressable onPress={send} style={styles.send} testID="send">
          <Text style={styles.sendText}>Send</Text>
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  list: { padding: 12, gap: 8 },
  bubble: { maxWidth: '78%', padding: 10, borderRadius: 14, flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  mine: { alignSelf: 'flex-end', backgroundColor: '#2563eb' },
  theirs: { alignSelf: 'flex-start', backgroundColor: '#e4e4e7' },
  body: { fontSize: 15, flexShrink: 1 },
  state: { fontSize: 11, opacity: 0.7 },
  composer: { flexDirection: 'row', padding: 10, gap: 8, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: '#d4d4d8' },
  input: { flex: 1, borderWidth: 1, borderColor: '#d4d4d8', borderRadius: 20, paddingHorizontal: 14, paddingVertical: 8 },
  send: { justifyContent: 'center', paddingHorizontal: 16, backgroundColor: '#2563eb', borderRadius: 20 },
  sendText: { color: 'white', fontWeight: '600' },
});
```

Delivery state is shown as a mark rather than a word so the transport stays invisible. The user should never learn what a "hop" is.

- [ ] **Step 3: Add mesh rows to Diagnostics**

Extend the existing screen with peers in range, queued frames, frames relayed, drop counts by reason, and bloom fill ratio, sourced from `observeDiagnostics()`.

- [ ] **Step 4: End-to-end test**

```yaml
# .maestro/send-message.yaml
appId: com.moshgrid.app
env:
  EXPO_PUBLIC_MESH: fake
---
- launchApp:
    clearState: true
- tapOn: "Contacts"
- tapOn:
    id: "contact-.*"
- tapOn:
    id: "composer"
- inputText: "hello from maestro"
- tapOn:
    id: "send"
- assertVisible: "hello from maestro"
```

- [ ] **Step 5: Run on the device**

```bash
EXPO_PUBLIC_MESH=fake npx expo run:android --device
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add chat screen wired to the mesh engine"
```

---

## Exit gates

- [ ] `npm run lint` → exit 0
- [ ] `npm test` → all pass, including relay, store-and-forward, and `a relay cannot read what it forwards`
- [ ] `cargo test --manifest-path crypto/Cargo.toml` → includes `ratchet_survives_out_of_order_delivery`
- [ ] `npm run check:licences` → exit 0
- [ ] `npm run bench:bloom` → measured rates within 2 percentage points of theoretical
- [ ] `npx maestro test .maestro/send-message.yaml` → pass
- [ ] **On the phone with `EXPO_PUBLIC_MESH=fake`: open a chat, send a message, watch it go `·` → `✓✓`** ← the gate
- [ ] Diagnostics shows non-zero peers, queued frames, and frames relayed

## Re-check before moving on

- [ ] Re-run Phase 0 and Phase 1 exit gates. All still green.
- [ ] **Confirm the seam holds:** `git log --stat` for this phase shows no later-phase reason to touch `src/app/`. From here on, any change to `src/app/` during Phases 3–8 means the interface is wrong.
- [ ] Confirm determinism: run `npm test -- mesh/engine` five times. Identical results every time. A flaky mesh test must be fixed now, not tolerated.
- [ ] Confirm drop reasons are counted: force a bad signature in a scratch test and check Diagnostics increments `bad_signature`.

## Updating the index

- [ ] Tick defects 4 and 8 in the [defect tracker](README.md#defect-regression-tracker)
- [ ] Fill the **Bloom FP rate @ 30 frames** row in the [measured-numbers ledger](README.md#measured-numbers-ledger)
- [ ] Set Phase 2 to `☑ Done` in [Progress](README.md#progress)

## What Phase 3 relies on

| Produced here | Used by |
|---|---|
| `Link` interface | Phase 3 implements `BleLink` against it |
| `RealMeshEngine` | Phase 3 reuses unchanged |
| `describeMeshEngineContract` | Phase 3 runs it against the real link |
| `Sim` | Phase 4 cross-checks real results against simulated |
| `getEngine()` in `src/mesh/index.ts` | Phase 3 edits this one line |
| Chat and Diagnostics screens | Phases 3–8 do not modify them |
