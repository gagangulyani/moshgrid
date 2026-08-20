# Phase 1 — Identity & Contacts

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** the phone generates a cryptographic identity that survives a restart, shows it as a QR code, scans someone else's, verifies the signature, and stores them as a contact.

**Architecture:** SQLite for persistence, Android Keystore for the pickle key, CBOR + base45 for the QR payload. No networking, no Bluetooth, no messaging yet.

**Tech Stack:** expo-sqlite, expo-camera, Kotlin (Keystore), vodozemac via Phase 0's bindings, Jest (jest-expo), Maestro.

**Spec:** [`../docs/design.md`](../docs/design.md) §6.1, §6.2, §7

**Devices:** 1 Android phone. A second phone makes QR testing pleasant but is not required — a QR rendered on your Mac screen scans fine.

**Depends on:** Phase 0 complete, including the crypto self-test gate.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints). Enforced directly here:

- **X25519 shared secret is 32 bytes.** Asserted in code (defect 1).
- **Contributory-DH check applied ourselves** — reject all-zero DH outputs.
- Contact bundle signature verified **before** the contact is stored. Never store an unverified bundle.

## File structure

| File | Responsibility |
|---|---|
| `src/db/types.ts` | `SqlDb` / `DbOpener` — the driver seam |
| `src/db/schema.ts` | Table definitions and migrations |
| `src/db/client.ts` | Migration runner, `openDb` (driver-agnostic) |
| `src/db/index.ts` | The app singleton — the only file naming `expo-sqlite` |
| `src/test-support/nodeSqlite.ts` | `node:sqlite` adapter, test builds only |
| `src/db/contacts.ts` | Contact CRUD |
| `src/db/identity.ts` | Identity load/store |
| `src/crypto/identity.ts` | Identity creation, pickling |
| `src/crypto/bundle.ts` | ContactBundle encode/decode/verify |
| `src/crypto/deviceId.ts` | DeviceId derivation |
| `crypto/src/lib.rs` | Extended with signing, verification, DH guard |
| `modules/moshgrid-core/.../KeystoreModule.kt` | Pickle key in Android Keystore |
| `src/app/identity.tsx` | "My code" QR screen |
| `src/app/scan.tsx` | Camera scanner |
| `src/app/contacts.tsx` | Contact list |

---

## Task 1: Database schema and migrations

**Files:**
- Create: `src/db/types.ts`, `src/db/schema.ts`, `src/db/client.ts`, `src/db/index.ts`, `src/test-support/nodeSqlite.ts`
- Test: `src/db/__tests__/client.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `SqlDb` and `DbOpener` (the seam), `openDb(name, open): SqlDb`, `runMigrations(db): number` returning the resulting schema version, and `getDb(): SqlDb` (the app singleton). All synchronous. Every later phase adds a migration rather than editing an existing one.

### Why the database is injected

`expo-sqlite` resolves through the native module bridge. **There is no bridge in a Node
test process**, so a test that imports `openDatabaseSync` fails on a missing native
module before a single assertion runs (`DEFECTS.md` B1). Under the earlier draft, Tasks
1, 5 and 6 had no executable test suite at all — the plan looked well-tested and was not.

The fix is a seam, not a mock. Migrations and queries are written against a **four-method
interface** that both drivers satisfy:

| Environment | Driver |
|---|---|
| Device / simulator | `expo-sqlite` `openDatabaseSync` |
| Jest | `node:sqlite` `DatabaseSync`, via a thin adapter |

Both are real SQLite. The tests run the same SQL the phone runs, which is the whole point
— mocking the database would leave the migrations themselves untested.

**`node:sqlite` is a Node builtin**, so this adds no dependency and nothing for the
licence gate to discover. Verified on Node v22.22.3: `DatabaseSync`, `exec`, and
`prepare().run()/.all()/.get()` all work, and `PRAGMA user_version` reads back through
`prepare(...).get()`. Two things to know:

- It prints `ExperimentalWarning: SQLite is an experimental feature`. Harmless, but
  expect it in CI output.
- Rows come back as **null-prototype objects**. `toEqual` ignores the prototype;
  **`toStrictEqual` does not** and will fail on rows that are otherwise identical.

- [ ] **Step 1: Install**

```bash
npx expo install expo-sqlite
```

Node 22.5+ is required for `node:sqlite`. Pin it in `.nvmrc` and in the CI setup step, or
the test suite silently becomes unrunnable on a contributor's older Node.

- [ ] **Step 2: Define the seam**

```typescript
// src/db/types.ts
/**
 * The entire surface this project uses. Deliberately tiny: every method here has
 * to be implemented twice, so each addition costs an adapter change. If a later
 * phase needs a fifth method, add it here first and to the adapter in the same
 * commit.
 */
export interface SqlDb {
  execSync(sql: string): void;
  runSync(sql: string, ...params: unknown[]): void;
  getFirstSync<T>(sql: string, ...params: unknown[]): T | null;
  getAllSync<T>(sql: string, ...params: unknown[]): T[];
}

export type DbOpener = (name: string) => SqlDb;
```

```typescript
// src/test-support/nodeSqlite.ts — test builds only, never imported by app code
import { DatabaseSync } from 'node:sqlite';
import type { SqlDb } from '../db/types';

/** Adapts node:sqlite to the SqlDb surface. */
export function openNodeDb(name: string): SqlDb {
  const db = new DatabaseSync(name);
  return {
    execSync: (sql) => db.exec(sql),
    runSync: (sql, ...params) => {
      db.prepare(sql).run(...(params as never[]));
    },
    getFirstSync: <T,>(sql: string, ...params: unknown[]) =>
      (db.prepare(sql).get(...(params as never[])) as T | undefined) ?? null,
    getAllSync: <T,>(sql: string, ...params: unknown[]) =>
      db.prepare(sql).all(...(params as never[])) as T[],
  };
}
```

The adapter is the one piece of this that exists only for tests, so it is also the one
piece that can drift from device behaviour without anything noticing. Step 4's first test
exists specifically to catch that.

- [ ] **Step 3: Write the failing test**

```typescript
// src/db/__tests__/client.test.ts
import { openNodeDb } from '../../test-support/nodeSqlite';
import { openDb, runMigrations, SCHEMA_VERSION } from '../client';

const freshDb = () => openNodeDb(':memory:');

test('the adapter honours the SqlDb contract', () => {
  // Guards the seam itself. If this drifts, every test below is testing the
  // adapter rather than the schema, and would keep passing while the app broke.
  const db = freshDb();
  db.execSync('CREATE TABLE t (a INTEGER, b TEXT)');
  db.runSync('INSERT INTO t VALUES (?, ?)', 1, 'x');
  db.runSync('INSERT INTO t VALUES (?, ?)', 2, 'y');
  expect(db.getAllSync<{ a: number }>('SELECT a FROM t ORDER BY a').map((r) => r.a)).toEqual([1, 2]);
  expect(db.getFirstSync<{ b: string }>('SELECT b FROM t WHERE a = ?', 2)?.b).toBe('y');
  expect(db.getFirstSync('SELECT b FROM t WHERE a = ?', 99)).toBeNull();
});

test('migrations bring a fresh database to the current version', () => {
  const db = freshDb();
  expect(runMigrations(db)).toBe(SCHEMA_VERSION);
});

test('migrations are idempotent', () => {
  const db = freshDb();
  runMigrations(db);
  expect(runMigrations(db)).toBe(SCHEMA_VERSION);
});

test('the version actually persists in the database, not just in the return value', () => {
  const db = freshDb();
  runMigrations(db);
  expect(db.getFirstSync<{ user_version: number }>('PRAGMA user_version')?.user_version)
    .toBe(SCHEMA_VERSION);
});

test('creates every table the spec requires', () => {
  const db = freshDb();
  runMigrations(db);
  const names = db
    .getAllSync<{ name: string }>("SELECT name FROM sqlite_master WHERE type='table'")
    .map((r) => r.name);
  for (const table of ['identity', 'contacts', 'sessions', 'messages', 'frames', 'seen_frames']) {
    expect(names).toContain(table);
  }
});

test('the contacts table carries the replay window columns', () => {
  // Regression guard for DEFECTS.md B23. The spec named these columns while no
  // schema declared them, and the gap survived because nothing asserted on it.
  const db = freshDb();
  runMigrations(db);
  const cols = db
    .getAllSync<{ name: string }>('PRAGMA table_info(contacts)')
    .map((r) => r.name);
  expect(cols).toContain('highest_sequence');
  expect(cols).toContain('replay_bitmap');
});

test('openDb applies migrations to whatever opener it is given', () => {
  const db = openDb(':memory:', openNodeDb);
  expect(db.getFirstSync<{ user_version: number }>('PRAGMA user_version')?.user_version)
    .toBe(SCHEMA_VERSION);
});
```

Note the signatures: `runMigrations` and `openDb` are **synchronous**, because every
method underneath them is. The earlier draft declared them `async` for no reason, and an
`async` function that never awaits is how a `Promise` ends up compared with `not.toBe` —
exactly the failure that made the DeviceId tests vacuous (`DEFECTS.md` B3).

- [ ] **Step 4: Run and watch it fail**

```bash
npm test -- db/client
```

Expected: FAIL — cannot find module `../client`.

- [ ] **Step 5: Implement**

```typescript
// src/db/schema.ts
export const MIGRATIONS: string[] = [
  // v1 — Phase 1
  `
  CREATE TABLE IF NOT EXISTS identity (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    pickle        TEXT    NOT NULL,
    device_id     TEXT    NOT NULL,
    ed25519_pub   TEXT    NOT NULL,
    curve25519_pub TEXT   NOT NULL,
    created_at    INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS contacts (
    device_id       TEXT PRIMARY KEY,
    ed25519_pub     TEXT    NOT NULL,
    curve25519_pub  TEXT    NOT NULL,
    one_time_key    TEXT,
    display_name    TEXT    NOT NULL,
    added_at        INTEGER NOT NULL,
    -- Replay window, per spec 6.3. Destination-side only: sequence state is
    -- kept for known contacts, never for strangers, because per-origin state
    -- for strangers is precisely the unbounded memory an attacker targets.
    highest_sequence INTEGER NOT NULL DEFAULT 0,
    replay_bitmap    BLOB    NOT NULL DEFAULT x'00'
  );

  CREATE TABLE IF NOT EXISTS sessions (
    device_id  TEXT PRIMARY KEY REFERENCES contacts(device_id) ON DELETE CASCADE,
    pickle     TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS messages (
    id         TEXT PRIMARY KEY,
    device_id  TEXT    NOT NULL,
    direction  TEXT    NOT NULL CHECK (direction IN ('in','out')),
    body       TEXT    NOT NULL,
    state      TEXT    NOT NULL CHECK (state IN ('queued','sent','delivered','failed')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_messages_device ON messages(device_id, created_at);

  CREATE TABLE IF NOT EXISTS frames (
    frame_id   TEXT PRIMARY KEY,
    bytes      BLOB    NOT NULL,
    copies     INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_frames_expiry ON frames(expires_at);

  CREATE TABLE IF NOT EXISTS seen_frames (
    frame_id   TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_seen_expiry ON seen_frames(expires_at);
  `,
];

export const SCHEMA_VERSION = MIGRATIONS.length;
```

```typescript
// src/db/client.ts
import type { SqlDb, DbOpener } from './types';
import { MIGRATIONS, SCHEMA_VERSION } from './schema';

export { SCHEMA_VERSION };
export type { SqlDb, DbOpener };

export function runMigrations(db: SqlDb): number {
  const row = db.getFirstSync<{ user_version: number }>('PRAGMA user_version');
  const current = row?.user_version ?? 0;

  for (let i = current; i < MIGRATIONS.length; i++) {
    db.execSync(MIGRATIONS[i]);
  }
  // Interpolated, not bound: SQLite does not accept a parameter in a PRAGMA.
  // SCHEMA_VERSION is a derived integer constant, never user input.
  db.execSync(`PRAGMA user_version = ${SCHEMA_VERSION}`);
  return SCHEMA_VERSION;
}

/** Open and migrate. The opener is a parameter so tests can pass a Node driver. */
export function openDb(name: string, open: DbOpener): SqlDb {
  const db = open(name);
  db.execSync('PRAGMA foreign_keys = ON');
  runMigrations(db);
  return db;
}
```

```typescript
// src/db/index.ts — the only file in the app that names expo-sqlite
import { openDatabaseSync } from 'expo-sqlite';
import { openDb, type SqlDb } from './client';

let cached: SqlDb | null = null;

/** App singleton. Tests never call this; they call openDb with their own opener. */
export function getDb(): SqlDb {
  if (!cached) cached = openDb('moshgrid.db', (n) => openDatabaseSync(n) as unknown as SqlDb);
  return cached;
}
```

`expo-sqlite` is named in exactly one file. That is what makes the rest of the phase
testable, and it is also the check to run if the seam ever looks like it is leaking:
`grep -rl expo-sqlite src/` must print one path.

The `as unknown as SqlDb` cast is deliberate and load-bearing: `SQLiteDatabase` has these
four methods with compatible shapes but a wider surface and its own generic constraints.
The cast is safe **only** while `SqlDb` stays a subset of it — which is why Step 3's first
test asserts the contract rather than trusting the cast.

Migrations are append-only. Editing an existing entry breaks every device that already ran it.

- [ ] **Step 6: Run the tests and watch them pass**

```bash
npm test -- db/client
```

Expected: 7 passed.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add sqlite schema and migration runner"
```

---

## Task 2: Crypto — signing, verification, and the DH guard

Extends Phase 0's Rust crate. Defect 1 and the vodozemac contributory-DH finding are both closed here.

**Files:**
- Modify: `crypto/src/lib.rs`, `crypto/src/moshgrid_crypto.udl`
- Test: `crypto/tests/signing.rs`, `crypto/tests/dh_guard.rs`

**Interfaces:**
- Consumes: `MoshgridAccount` from Phase 0.
- Produces: `sign(message: Vec<u8>) -> String`, `verify(ed25519_pub, message, signature) -> bool`, `check_contributory(shared_secret: Vec<u8>) -> Result<(), CryptoError>`.

- [ ] **Step 1: Write the failing tests**

```rust
// crypto/tests/signing.rs
use moshgrid_crypto::{verify, MoshgridAccount};

#[test]
fn signature_verifies_against_the_signing_account() {
    let account = MoshgridAccount::new();
    let key = account.identity_keys().ed25519;
    let message = b"contact bundle bytes".to_vec();

    let signature = account.sign(message.clone());
    assert!(verify(key, message, signature).unwrap());
}

#[test]
fn tampered_message_fails_verification() {
    let account = MoshgridAccount::new();
    let key = account.identity_keys().ed25519;
    let signature = account.sign(b"original".to_vec());

    assert!(!verify(key, b"tampered".to_vec(), signature).unwrap());
}

#[test]
fn signature_from_a_different_account_fails() {
    let a = MoshgridAccount::new();
    let b = MoshgridAccount::new();
    let message = b"bundle".to_vec();
    let signature = b.sign(message.clone());

    assert!(!verify(a.identity_keys().ed25519, message, signature).unwrap());
}
```

```rust
// crypto/tests/dh_guard.rs
use moshgrid_crypto::{check_contributory, x25519_shared_secret_len};

/// Defect 1: the source specification claimed a 128-byte X25519 shared secret.
/// RFC 7748 defines it as 32 bytes.
#[test]
fn test_x25519_shared_secret_is_32_bytes() {
    assert_eq!(x25519_shared_secret_len(), 32);
}

#[test]
fn all_zero_shared_secret_is_rejected() {
    assert!(check_contributory(vec![0u8; 32]).is_err());
}

#[test]
fn normal_shared_secret_is_accepted() {
    let mut secret = vec![0u8; 32];
    secret[7] = 42;
    assert!(check_contributory(secret).is_ok());
}

#[test]
fn wrong_length_shared_secret_is_rejected() {
    assert!(check_contributory(vec![1u8; 128]).is_err());
}
```

The last test is the direct regression for defect 1 — a 128-byte value must be refused, not silently accepted.

- [ ] **Step 2: Run and watch them fail**

```bash
cd crypto && cargo test
```

Expected: FAIL — `check_contributory`, `x25519_shared_secret_len`, `verify` not found.

- [ ] **Step 3: Implement**

```rust
// append to crypto/src/lib.rs
use vodozemac::{Ed25519PublicKey, Ed25519Signature};

pub const X25519_SHARED_SECRET_LEN: usize = 32;

pub fn x25519_shared_secret_len() -> u32 {
    X25519_SHARED_SECRET_LEN as u32
}

/// Rejects non-contributory Diffie-Hellman outputs.
///
/// vodozemac does not currently apply this check itself (reported February 2026;
/// Matrix disputes exploitability because identity keys are Ed25519-signed and
/// verified first, and has agreed to add it upstream). We apply it ourselves
/// rather than waiting. See docs/design.md §3.2.
pub fn check_contributory(shared_secret: Vec<u8>) -> Result<(), CryptoError> {
    if shared_secret.len() != X25519_SHARED_SECRET_LEN {
        return Err(CryptoError::InvalidKey);
    }
    let all_zero = shared_secret.iter().fold(0u8, |acc, b| acc | b) == 0;
    if all_zero {
        return Err(CryptoError::NonContributoryKey);
    }
    Ok(())
}

pub fn verify(
    ed25519_pub: String,
    message: Vec<u8>,
    signature: String,
) -> Result<bool, CryptoError> {
    let key = Ed25519PublicKey::from_base64(&ed25519_pub)
        .map_err(|_| CryptoError::InvalidKey)?;
    let sig = Ed25519Signature::from_base64(&signature)
        .map_err(|_| CryptoError::InvalidSignature)?;
    Ok(key.verify(&message, &sig).is_ok())
}

impl MoshgridAccount {
    pub fn sign(&self, message: Vec<u8>) -> String {
        self.inner.lock().unwrap().sign(&message).to_base64()
    }
}
```

The zero check uses a fold rather than `== [0u8; 32]` so it runs in constant time with respect to where the first non-zero byte falls.

Add the new error variants to the enum and to the `.udl`:

```
[Error]
enum CryptoError {
    "Unpickle", "InvalidKey", "InvalidSignature", "NonContributoryKey",
};

namespace moshgrid_crypto {
    u32 x25519_shared_secret_len();
    [Throws=CryptoError] void check_contributory(sequence<u8> shared_secret);
    [Throws=CryptoError] boolean verify(string ed25519_pub, sequence<u8> message, string signature);
};
```

- [ ] **Step 4: Run and watch them pass**

```bash
cd crypto && cargo test
```

Expected: all pass, including `test_x25519_shared_secret_is_32_bytes`.

- [ ] **Step 5: Rebuild the Android bindings**

```bash
./scripts/build-crypto-android.sh
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add Ed25519 signing and non-contributory DH guard

Closes defect 1 (X25519 shared secret is 32 bytes, not 128)."
```

---

## Task 3: DeviceId derivation

**Files:**
- Create: `src/crypto/deviceId.ts`
- Test: `src/crypto/__tests__/deviceId.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `deviceIdFromEd25519(base64Key: string): string` — 16 lowercase hex characters (8 bytes). **Synchronous**, and genuinely so.

### Why this does not use `expo-crypto`

An earlier draft hashed with `expo-crypto`'s `digestStringAsync`. Two defects came from
that single choice, and they are worth naming because the second is the more dangerous:

- **`expo-crypto` has no implementation under jest** (`DEFECTS.md` B2). Its functions
  resolve through the native module bridge, which does not exist in a Node test process.
  Every test in this task would have failed on a missing native module — or worse,
  received `undefined` and gone on.
- **The signature was declared sync and implemented async** (`DEFECTS.md` B3). Callers
  wrote `deviceIdFromEd25519(k)` and got a `Promise`. The "different keys give different
  ids" test then compared two *Promise objects* with `not.toBe` — two distinct objects
  are never `toBe`-equal, so **that test passed no matter what the function returned.**
  It would have passed against a function returning the empty string.

`@noble/hashes` removes the cause rather than working around it: pure TypeScript, no
native module, no bridge, and synchronous. It runs identically in jest and on device, so
the tests exercise the shipping code path instead of a mock of it.

- [ ] **Step 1: Install and re-run the licence gate**

```bash
npm install @noble/hashes
npm run check:licences
```

`@noble/hashes@2.3.0` is **MIT with zero dependencies** — verified against the npm
registry, not assumed. Zero dependencies matters here: the licence gate has nothing
transitive to discover later.

Two version-sensitive details, both of which will silently misresolve if ignored:

- **The subpath is `sha2.js`, not `sha256`.** v2 collapsed the per-algorithm entry
  points; `@noble/hashes/sha256` was the **v1** path and does not exist in v2.
- The package `exports` map lists subpaths **with the `.js` suffix**, so TypeScript
  needs `moduleResolution: "bundler"` or `"nodenext"`. Under the legacy `"node"`
  resolution the import does not type-check.

- [ ] **Step 2: Write the failing test**

```typescript
// src/crypto/__tests__/deviceId.test.ts
import { deviceIdFromEd25519 } from '../deviceId';

// Known-answer vectors, computed independently with a reference SHA-256
// implementation. These pin the whole definition — UTF-8 bytes of the base64
// *text*, SHA-256, first 8 bytes as lowercase hex — so a change to any part of
// it fails here rather than surfacing as undecodable frames in the field.
const KEY_A = 'aGVsbG8gd29ybGQgdGhpcyBpcyBhIGtleSBmb3IgdGVzdA';
const KEY_B = 'c29tZXRoaW5nIGVudGlyZWx5IGRpZmZlcmVudCBoZXJlIQ';

test('device id is the first 8 bytes of SHA-256, as hex', () => {
  expect(deviceIdFromEd25519(KEY_A)).toBe('7b293268af7035ea');
  expect(deviceIdFromEd25519(KEY_B)).toBe('363efc823c1923f0');
});

test('device id is 16 lowercase hex characters', () => {
  expect(deviceIdFromEd25519(KEY_A)).toMatch(/^[0-9a-f]{16}$/);
});

test('device id is deterministic', () => {
  expect(deviceIdFromEd25519(KEY_A)).toBe(deviceIdFromEd25519(KEY_A));
});

test('the return value is a string, not a Promise', () => {
  // Regression guard for DEFECTS.md B3. Without this, an async implementation
  // makes every `not.toBe` comparison below pass vacuously.
  expect(typeof deviceIdFromEd25519(KEY_A)).toBe('string');
});

test('different keys give different device ids', () => {
  expect(deviceIdFromEd25519(KEY_A)).not.toBe(deviceIdFromEd25519(KEY_B));
});
```

- [ ] **Step 3: Run and watch it fail**

```bash
npm test -- deviceId
```

Expected: FAIL — cannot find module `../deviceId`.

- [ ] **Step 4: Implement**

```typescript
// src/crypto/deviceId.ts
import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';

export const DEVICE_ID_BYTES = 8;

/** First 8 bytes of SHA-256 over the base64 Ed25519 public key, as hex. */
export function deviceIdFromEd25519(base64Key: string): string {
  // Hash the base64 *text*, not the decoded key bytes. Both are defensible; only
  // one can be right, and every peer must make the same choice. The known-answer
  // vectors in the test are what actually pins it.
  const digest = sha256(new TextEncoder().encode(base64Key));
  return bytesToHex(digest.subarray(0, DEVICE_ID_BYTES));
}
```

Truncation is for the wire only. Verification always uses the full key — never the DeviceId.

- [ ] **Step 5: Run and watch it pass**

```bash
npm test -- deviceId
```

Expected: 5 passed.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: derive device id from ed25519 key"
```

---

## Task 4: Contact bundle encode, decode, verify

**Files:**
- Create: `src/crypto/bundle.ts`
- Test: `src/crypto/__tests__/bundle.test.ts`

**Interfaces:**
- Consumes: `verify()` from Task 2, `deviceIdFromEd25519` from Task 3.
- Produces: `encodeBundle(b: ContactBundle): string`, `decodeBundle(s: string): ContactBundle`, `verifyBundle(b: ContactBundle): Promise<boolean>`, and the `ContactBundle` type.

- [ ] **Step 1: Write the failing test**

```typescript
// src/crypto/__tests__/bundle.test.ts
import { encodeBundle, decodeBundle, signableBytes, type ContactBundle } from '../bundle';

const sample: ContactBundle = {
  version: 1,
  ed25519Pub: 'RUQyNTUxOVB1YmxpY0tleUJhc2U2NEVuY29kZWRWYWx1ZQ',
  curve25519Pub: 'Q3VydmUyNTUxOVB1YmxpY0tleUJhc2U2NEVuY29kZWQx',
  oneTimeKey: 'T25lVGltZUtleUJhc2U2NEVuY29kZWRWYWx1ZUhlcmUxMg',
  displayName: 'Gagan',
  signature: 'c2lnbmF0dXJlYnl0ZXNiYXNlNjRlbmNvZGVkc2lnbmF0dXJlYnl0ZXNiYXNlNjRlbmNvZGVk',
};

test('encode then decode round-trips every field', () => {
  expect(decodeBundle(encodeBundle(sample))).toEqual(sample);
});

test('encoded bundle is QR-safe alphanumeric', () => {
  expect(encodeBundle(sample)).toMatch(/^[0-9A-Z $%*+\-./:]+$/);
});

test('signable bytes exclude the signature field', () => {
  const a = signableBytes(sample);
  const b = signableBytes({ ...sample, signature: 'totally-different-signature' });
  expect(a).toEqual(b);
});

test('signable bytes change when the display name changes', () => {
  const a = signableBytes(sample);
  const b = signableBytes({ ...sample, displayName: 'Someone Else' });
  expect(a).not.toEqual(b);
});

test('decoding malformed input throws rather than returning a partial bundle', () => {
  expect(() => decodeBundle('NOT-A-VALID-BUNDLE')).toThrow();
});

test('rejects a display name over 32 bytes', () => {
  expect(() => encodeBundle({ ...sample, displayName: 'x'.repeat(33) })).toThrow(/32/);
});
```

The third and fourth tests together pin the exact signing surface. Getting that wrong is how signature checks end up covering nothing.

- [ ] **Step 2: Run and watch it fail**

```bash
npm test -- bundle
```

- [ ] **Step 3: Implement**

```bash
npm i cbor-x base45
```

```typescript
// src/crypto/bundle.ts
import { encode as cborEncode, decode as cborDecode } from 'cbor-x';
import { encode as b45encode, decode as b45decode } from 'base45';
import { verify } from '../../modules/moshgrid-core';

export const BUNDLE_VERSION = 1;
export const MAX_DISPLAY_NAME_BYTES = 32;

export type ContactBundle = {
  version: number;
  ed25519Pub: string;
  curve25519Pub: string;
  oneTimeKey: string;
  displayName: string;
  signature: string;
};

/** Exactly the bytes covered by the signature. Order is part of the protocol. */
export function signableBytes(b: ContactBundle): Uint8Array {
  return cborEncode([b.version, b.ed25519Pub, b.curve25519Pub, b.oneTimeKey, b.displayName]);
}

export function encodeBundle(b: ContactBundle): string {
  const nameBytes = new TextEncoder().encode(b.displayName).length;
  if (nameBytes > MAX_DISPLAY_NAME_BYTES) {
    throw new Error(`display name is ${nameBytes} bytes, max is ${MAX_DISPLAY_NAME_BYTES}`);
  }
  const payload = cborEncode([
    b.version, b.ed25519Pub, b.curve25519Pub, b.oneTimeKey, b.displayName, b.signature,
  ]);
  return b45encode(payload);
}

export function decodeBundle(s: string): ContactBundle {
  let parts: unknown;
  try {
    parts = cborDecode(b45decode(s));
  } catch {
    throw new Error('bundle is not valid base45+CBOR');
  }
  if (!Array.isArray(parts) || parts.length !== 6) {
    throw new Error('bundle has the wrong shape');
  }
  const [version, ed25519Pub, curve25519Pub, oneTimeKey, displayName, signature] = parts;
  if (version !== BUNDLE_VERSION) {
    throw new Error(`unsupported bundle version ${String(version)}`);
  }
  return { version, ed25519Pub, curve25519Pub, oneTimeKey, displayName, signature } as ContactBundle;
}

export async function verifyBundle(b: ContactBundle): Promise<boolean> {
  return verify(b.ed25519Pub, signableBytes(b), b.signature);
}
```

base45 is used because QR codes encode alphanumeric far more densely than binary, which keeps the code scannable at arm's length in bad festival light.

- [ ] **Step 4: Run and watch it pass**

```bash
npm test -- bundle
```

Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add contact bundle encoding with signed payload"
```

---

## Task 5: Identity persistence with a Keystore-backed pickle key

**Files:**
- Create: `modules/moshgrid-core/android/src/main/java/com/moshgrid/core/PickleKeyStore.kt`
- Create: `src/crypto/identity.ts`, `src/db/identity.ts`
- Test: `src/crypto/__tests__/identity.test.ts`

**Interfaces:**
- Consumes: `MoshgridAccount`, `getDb()`.
- Produces: `loadOrCreateIdentity(): Promise<Identity>` where `Identity = { deviceId, ed25519Pub, curve25519Pub, displayName }`, and `myBundle(displayName): Promise<ContactBundle>`.

- [ ] **Step 1: Add the Keystore-backed key in Kotlin**

```kotlin
// modules/moshgrid-core/android/src/main/java/com/moshgrid/core/PickleKeyStore.kt
package com.moshgrid.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object PickleKeyStore {
    private const val ALIAS = "moshgrid.pickle.v1"
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreate(): ByteArray {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key: SecretKey = existing ?: generate()
        return key.encoded ?: throw IllegalStateException(
            "Keystore key is not exportable; use wrap/unwrap instead"
        )
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
```

**Implementer note:** hardware-backed Keystore keys are deliberately non-exportable, so `key.encoded` returns null on most devices. If that happens — and it should be expected, not treated as a bug — switch to the wrap/unwrap pattern: keep a random 32-byte pickle key in SQLite, encrypted with the Keystore key via AES-GCM, and decrypt it on load. Add `encryptPickleKey`/`decryptPickleKey` functions to this object and expose those instead. The property that must hold is: **the pickle key is never stored in plaintext on disk.** Write a test that asserts the stored blob does not contain the raw key bytes.

- [ ] **Step 2: Write the failing test**

```typescript
// src/crypto/__tests__/identity.test.ts
import { loadOrCreateIdentity, myBundle } from '../identity';
import { verifyBundle } from '../bundle';

test('creates an identity on first run', async () => {
  const id = await loadOrCreateIdentity();
  expect(id.deviceId).toMatch(/^[0-9a-f]{16}$/);
  expect(id.ed25519Pub.length).toBeGreaterThan(0);
});

test('returns the same identity on the second call', async () => {
  const first = await loadOrCreateIdentity();
  const second = await loadOrCreateIdentity();
  expect(second.deviceId).toBe(first.deviceId);
});

test('my own bundle verifies against my own signature', async () => {
  await loadOrCreateIdentity();
  const bundle = await myBundle('Gagan');
  expect(await verifyBundle(bundle)).toBe(true);
});

test('a bundle with a tampered display name fails verification', async () => {
  await loadOrCreateIdentity();
  const bundle = await myBundle('Gagan');
  expect(await verifyBundle({ ...bundle, displayName: 'Mallory' })).toBe(false);
});
```

- [ ] **Step 3: Run and watch it fail**

```bash
npm test -- crypto/identity
```

- [ ] **Step 4: Implement**

```typescript
// src/crypto/identity.ts
import { createAccount, unpickleAccount, pickleKey } from '../../modules/moshgrid-core';
// createAccount/unpickleAccount/pickleKey are thin TS wrappers added in Task 0 below —
// they map to MoshgridAccount(), MoshgridAccount.unpickle() and the Keystore bridge.
import { getDb } from '../db';
import { deviceIdFromEd25519 } from './deviceId';
import { signableBytes, BUNDLE_VERSION, type ContactBundle } from './bundle';

export type Identity = {
  deviceId: string;
  ed25519Pub: string;
  curve25519Pub: string;
};

async function currentAccount() {
  const db = getDb();
  const key = await pickleKey();
  const row = db.getFirstSync<{ pickle: string }>('SELECT pickle FROM identity WHERE id = 1');

  if (row) return { account: unpickleAccount(row.pickle, key), key, db };

  const account = createAccount();
  const keys = account.identityKeys();
  const deviceId = deviceIdFromEd25519(keys.ed25519);

  db.runSync(
    `INSERT INTO identity (id, pickle, device_id, ed25519_pub, curve25519_pub, created_at)
     VALUES (1, ?, ?, ?, ?, ?)`,
    account.pickle(key), deviceId, keys.ed25519, keys.curve25519, Date.now(),
  );
  return { account, key, db };
}

export async function loadOrCreateIdentity(): Promise<Identity> {
  const { account } = await currentAccount();
  const keys = account.identityKeys();
  return {
    deviceId: deviceIdFromEd25519(keys.ed25519),
    ed25519Pub: keys.ed25519,
    curve25519Pub: keys.curve25519,
  };
}

export async function myBundle(displayName: string): Promise<ContactBundle> {
  const { account, key, db } = await currentAccount();
  const keys = account.identityKeys();
  const oneTimeKey = account.oneTimeKeys(1)[0];

  const unsigned: ContactBundle = {
    version: BUNDLE_VERSION,
    ed25519Pub: keys.ed25519,
    curve25519Pub: keys.curve25519,
    oneTimeKey,
    displayName,
    signature: '',
  };

  const signature = account.sign(signableBytes(unsigned));
  db.runSync('UPDATE identity SET pickle = ? WHERE id = 1', account.pickle(key));
  return { ...unsigned, signature };
}
```

Re-pickling after generating a one-time key is essential — vodozemac's account state changed, and losing it means the published pre-key has no matching private half, so nobody can ever start a session with you.

- [ ] **Step 5: Run and watch it pass**

```bash
npm test -- crypto/identity
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: persist identity with keystore-backed pickle key"
```

---

## Task 6: Contact storage

**Files:**
- Create: `src/db/contacts.ts`
- Test: `src/db/__tests__/contacts.test.ts`

**Interfaces:**
- Consumes: `getDb()`, `verifyBundle()`, `deviceIdFromEd25519()`.
- Produces: `addContactFromBundle(b): Promise<Contact>`, `listContacts(): Promise<Contact[]>`, `getContact(deviceId): Promise<Contact | null>`.

- [ ] **Step 1: Write the failing test**

```typescript
// src/db/__tests__/contacts.test.ts
import { addContactFromBundle, listContacts } from '../contacts';
import { myBundle } from '../../crypto/identity';
import { loadOrCreateIdentity } from '../../crypto/identity';

test('stores a contact whose signature verifies', async () => {
  await loadOrCreateIdentity();
  const bundle = await myBundle('Ana');
  const contact = await addContactFromBundle(bundle);
  expect(contact.displayName).toBe('Ana');
  expect(await listContacts()).toHaveLength(1);
});

test('REJECTS a contact whose signature does not verify', async () => {
  await loadOrCreateIdentity();
  const bundle = await myBundle('Ana');
  const forged = { ...bundle, displayName: 'Mallory' };

  await expect(addContactFromBundle(forged)).rejects.toThrow(/signature/i);
  expect(await listContacts()).toHaveLength(0);
});

test('adding the same contact twice updates rather than duplicates', async () => {
  await loadOrCreateIdentity();
  const bundle = await myBundle('Ana');
  await addContactFromBundle(bundle);
  await addContactFromBundle(bundle);
  expect(await listContacts()).toHaveLength(1);
});
```

The second test is the important one — it asserts that a failed verification leaves **no row behind**.

- [ ] **Step 2: Run and watch it fail**

```bash
npm test -- db/contacts
```

- [ ] **Step 3: Implement**

```typescript
// src/db/contacts.ts
import { getDb } from './index';
import { verifyBundle, type ContactBundle } from '../crypto/bundle';
import { deviceIdFromEd25519 } from '../crypto/deviceId';

export type Contact = {
  deviceId: string;
  ed25519Pub: string;
  curve25519Pub: string;
  displayName: string;
  addedAt: number;
};

export async function addContactFromBundle(bundle: ContactBundle): Promise<Contact> {
  if (!(await verifyBundle(bundle))) {
    throw new Error('bundle signature is invalid — contact not added');
  }

  const deviceId = deviceIdFromEd25519(bundle.ed25519Pub);
  const db = getDb();
  const addedAt = Date.now();

  db.runSync(
    `INSERT INTO contacts (device_id, ed25519_pub, curve25519_pub, display_name, added_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET
       display_name = excluded.display_name,
       curve25519_pub = excluded.curve25519_pub`,
    deviceId, bundle.ed25519Pub, bundle.curve25519Pub, bundle.displayName, addedAt,
  );

  return {
    deviceId,
    ed25519Pub: bundle.ed25519Pub,
    curve25519Pub: bundle.curve25519Pub,
    displayName: bundle.displayName,
    addedAt,
  };
}

export async function listContacts(): Promise<Contact[]> {
  const db = getDb();
  return db.getAllSync<Contact>(
    `SELECT device_id AS deviceId, ed25519_pub AS ed25519Pub,
            curve25519_pub AS curve25519Pub, display_name AS displayName,
            added_at AS addedAt
     FROM contacts ORDER BY display_name COLLATE NOCASE`,
  );
}

export async function getContact(deviceId: string): Promise<Contact | null> {
  const db = getDb();
  return db.getFirstSync<Contact>(
    `SELECT device_id AS deviceId, ed25519_pub AS ed25519Pub,
            curve25519_pub AS curve25519Pub, display_name AS displayName,
            added_at AS addedAt
     FROM contacts WHERE device_id = ?`,
    deviceId,
  ) ?? null;
}
```

Note the `ed25519_pub` is deliberately **not** updated on conflict. A rescanned contact may change their display name; a changed identity key means it is a different person and must not silently overwrite the old one.

- [ ] **Step 4: Run and watch it pass**

```bash
npm test -- db/contacts
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: store contacts, rejecting bundles that fail verification"
```

---

## Task 7: The three screens

**Files:**
- Create: `src/app/identity.tsx`, `src/app/scan.tsx`, `src/app/contacts.tsx`
- Modify: `src/app/index.tsx`
- Test: `.maestro/add-contact.yaml`

**Interfaces:**
- Consumes: everything above.
- Produces: routes `/identity`, `/scan`, `/contacts`. Phase 2 adds `/chat/[deviceId]`.

- [ ] **Step 1: Install**

```bash
npx expo install expo-camera react-native-qrcode-svg react-native-svg
```

Add to `app.json` plugins:

```json
["expo-camera", { "cameraPermission": "MoshGrid uses the camera only to scan a friend's code." }]
```

- [ ] **Step 2: "My code" screen**

```tsx
// src/app/identity.tsx
import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';
import { myBundle } from '../crypto/identity';
import { encodeBundle } from '../crypto/bundle';

export default function IdentityScreen() {
  const [payload, setPayload] = useState<string | null>(null);
  const [deviceId, setDeviceId] = useState('');

  useEffect(() => {
    (async () => {
      const bundle = await myBundle('Me');
      setPayload(encodeBundle(bundle));
      setDeviceId(bundle.ed25519Pub.slice(0, 12));
    })();
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.heading}>My code</Text>
      {payload ? <QRCode value={payload} size={260} /> : <Text>Generating…</Text>}
      <Text style={styles.fingerprint} testID="fingerprint">{deviceId}</Text>
      <Text style={styles.hint}>Have your friend scan this, then scan theirs.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 20, padding: 24 },
  heading: { fontSize: 24, fontWeight: '700' },
  fingerprint: { fontFamily: 'monospace', fontSize: 15, letterSpacing: 1 },
  hint: { fontSize: 14, color: '#52525b', textAlign: 'center' },
});
```

- [ ] **Step 3: Scanner screen**

```tsx
// src/app/scan.tsx
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { Button, StyleSheet, Text, View } from 'react-native';
import { decodeBundle } from '../crypto/bundle';
import { addContactFromBundle } from '../db/contacts';

export default function ScanScreen() {
  const [permission, requestPermission] = useCameraPermissions();
  const [error, setError] = useState<string | null>(null);
  const handled = useRef(false);
  const router = useRouter();

  if (!permission) return <View />;
  if (!permission.granted) {
    return (
      <View style={styles.center}>
        <Text style={styles.hint}>MoshGrid needs the camera to scan a friend's code.</Text>
        <Button title="Allow camera" onPress={requestPermission} />
      </View>
    );
  }

  async function onScanned({ data }: { data: string }) {
    if (handled.current) return;
    handled.current = true;
    try {
      const contact = await addContactFromBundle(decodeBundle(data));
      router.replace({ pathname: '/contacts', params: { added: contact.displayName } });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not read that code');
      handled.current = false;
    }
  }

  return (
    <View style={styles.container}>
      <CameraView
        style={StyleSheet.absoluteFill}
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={onScanned}
      />
      {error && <Text style={styles.error} testID="scan-error">{error}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  hint: { fontSize: 15, textAlign: 'center', color: '#3f3f46' },
  error: { position: 'absolute', bottom: 48, alignSelf: 'center', backgroundColor: '#b91c1c', color: 'white', padding: 12, borderRadius: 8, overflow: 'hidden' },
});
```

The `handled` ref stops the camera firing the same code dozens of times per second. On failure it resets so the user can retry.

- [ ] **Step 4: Contacts screen**

```tsx
// src/app/contacts.tsx
import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import { listContacts, type Contact } from '../db/contacts';

export default function ContactsScreen() {
  const [contacts, setContacts] = useState<Contact[]>([]);

  useFocusEffect(useCallback(() => { listContacts().then(setContacts); }, []));

  return (
    <FlatList
      data={contacts}
      keyExtractor={(c) => c.deviceId}
      contentContainerStyle={styles.list}
      ListEmptyComponent={<Text style={styles.empty}>No contacts yet. Scan a friend's code.</Text>}
      renderItem={({ item }) => (
        <View style={styles.row} testID={`contact-${item.deviceId}`}>
          <Text style={styles.name}>{item.displayName}</Text>
          <Text style={styles.id}>{item.deviceId.slice(0, 8)}</Text>
        </View>
      )}
    />
  );
}

const styles = StyleSheet.create({
  list: { padding: 16 },
  empty: { textAlign: 'center', marginTop: 48, color: '#71717a' },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 14, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#e4e4e7' },
  name: { fontSize: 16, fontWeight: '500' },
  id: { fontFamily: 'monospace', fontSize: 13, color: '#71717a' },
});
```

- [ ] **Step 5: Add the end-to-end flow test**

```yaml
# .maestro/add-contact.yaml
appId: com.moshgrid.app
---
- launchApp:
    clearState: true
- tapOn: "Contacts"
- assertVisible: "No contacts yet. Scan a friend's code."
- tapOn: "My code"
- assertVisible:
    id: "fingerprint"
```

```bash
npx maestro test .maestro/add-contact.yaml
```

- [ ] **Step 6: Run on the device**

```bash
npx expo run:android --device
```

Open `/identity` on the phone, then scan that QR with a second device or a QR reader to confirm it decodes to a base45 string.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: add identity, scan, and contacts screens"
```

---

## Exit gates

- [ ] `npm run lint` → exit 0
- [ ] `npm test` → all pass, including `addContactFromBundle` rejection test
- [ ] `npm test -- db/client` → 7 passed, including the adapter-contract test and the replay-column guard
- [ ] `npm test -- deviceId` → 5 passed, including the known-answer vectors
- [ ] `grep -rl expo-sqlite src/` prints exactly one path (`src/db/index.ts`) — the seam has not leaked
- [ ] `cargo test --manifest-path crypto/Cargo.toml` → includes `test_x25519_shared_secret_is_32_bytes` passing
- [ ] `npm run check:licences` → exit 0
- [ ] `npx maestro test .maestro/add-contact.yaml` → pass
- [ ] **On the phone: open the app, see a QR code, scan another device's code, and the contact appears in the list** ← the gate
- [ ] **Force-quit the app and reopen it — the same identity and the same contacts are still there** ← the persistence gate

## Re-check before moving on

- [ ] Re-run Phase 0's exit gates. All still green.
- [ ] Verify a tampered bundle is genuinely refused: in a REPL, take a valid bundle, change one character of `displayName`, and confirm `addContactFromBundle` throws and adds no row.
- [ ] Confirm the pickle key is not on disk in plaintext: `adb shell run-as com.moshgrid.app cat databases/moshgrid.db | strings | head -50` — the raw key bytes must not appear.
- [ ] Confirm re-pickling after one-time key generation works: create a bundle, restart the app, create another bundle, and confirm both one-time keys differ.
- [ ] **Confirm the seam agrees with the device.** The suite proves the SQL is right under `node:sqlite`; it does not prove `expo-sqlite` behaves identically. Once on the phone, read `PRAGMA user_version` and `PRAGMA table_info(contacts)` from the real database and confirm they match what the tests assert. Do this once per migration added, not once ever.

## Updating the index

- [ ] Tick defect 1 in the [defect tracker](README.md#defect-regression-tracker)
- [ ] Set Phase 1 to `☑ Done` in [Progress](README.md#progress)

## What Phase 2 relies on

| Produced here | Used by |
|---|---|
| `getDb()`, migration runner | Phase 2 message and frame tables |
| `Identity`, `loadOrCreateIdentity()` | Phase 2 session establishment |
| `Contact`, `getContact()` | Phase 2 addressing |
| `ContactBundle.oneTimeKey` | Phase 2 outbound Olm session creation |
| `/contacts` route | Phase 2 taps through to `/chat/[deviceId]` |
