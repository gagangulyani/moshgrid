# Phase 0 — Foundations & Gates

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Steps use `- [ ]` checkboxes.

**Goal:** prove the three things everything else is built on — Expo runs a custom Kotlin module on a real phone, vodozemac binds to Kotlin, and CI blocks copyleft dependencies — and put a Diagnostics screen on the phone that shows all three are true.

**Architecture:** an Expo app with a dev client, one Kotlin Expo Module, and a Rust crate that wraps vodozemac behind UniFFI-generated Kotlin bindings. Nothing about messaging or Bluetooth yet.

**Tech Stack:** Expo (dev client, prebuild), TypeScript, Kotlin, Rust, UniFFI, cargo-ndk, Jest (jest-expo), JUnit, GitHub Actions.

**Spec:** [`../docs/design.md`](../docs/design.md)

**Devices:** 1 Android phone, USB debugging enabled.

## Why this phase exists

The source PDF had a "Phase 0 GO/NO-GO gate" and it was pointed at the wrong thing — it gated on a library that turned out to be AGPL. This phase gates on what can actually kill the project:

1. Can Expo run our own Kotlin native code on a real device? (The PDF claimed no. It's wrong, but we verify rather than argue.)
2. Can vodozemac be called from Kotlin at all?
3. Can we keep AGPL out of the tree automatically, forever, without remembering to?

If any answer is no, we find out in days rather than months.

## Global constraints

All constraints from [`README.md`](README.md#global-constraints) apply. The ones this phase enforces directly:

- All runtime dependencies MIT, Apache-2.0, BSD, or ISC. **No GPL or AGPL.**
- Crypto is vodozemac (Apache-2.0). Never `libsignal` (AGPL-3.0-only).
- TDD without exception.

## File structure

| File | Responsibility |
|---|---|
| `package.json` | JS deps, scripts |
| `app.json` | Expo config, plugins, Android package name |
| `src/app/_layout.tsx` | Root navigation |
| `src/app/diagnostics.tsx` | The visible deliverable of this phase |
| `src/lib/version.ts` | Build/version constants surfaced in Diagnostics |
| `modules/moshgrid-core/` | Kotlin Expo Module (native seam) |
| `modules/moshgrid-core/src/MoshgridCoreModule.kt` | Module definition, exported functions |
| `modules/moshgrid-core/index.ts` | TypeScript face of the module |
| `modules/moshgrid-core/android/.../AccountRef.kt` | `SharedObject` holding a live vodozemac account |
| `crypto/` | Rust crate wrapping vodozemac |
| `crypto/src/lib.rs` | UniFFI-exported crypto surface |
| `crypto/src/moshgrid_crypto.udl` | UniFFI interface definition |
| `scripts/check-licences.mjs` | Dependency licence gate |
| `.github/workflows/ci.yml` | CI |

---

## Task 1: Expo app that runs on the phone

**Files:**
- Create: `package.json`, `app.json`, `tsconfig.json`, `src/app/_layout.tsx`, `src/app/index.tsx`
- Create: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: a runnable Expo dev-client app. Later tasks add screens under `src/app/`.

- [ ] **Step 1: Scaffold the project**

```bash
cd ~/projects/MoshGrid
npx create-expo-app@latest . --template blank-typescript
```

If the directory is not empty, scaffold into a temp dir and move files in, keeping `docs/` and `plans/`.

- [ ] **Step 2: Record the exact SDK version**

Do not assume a version. Read what was installed and write it down:

```bash
node -p "require('./package.json').dependencies.expo"
```

Record the printed value in `plans/README.md` under a new **Toolchain versions** heading. Every later phase assumes this version.

- [ ] **Step 3: Add Expo Router and the dev client**

```bash
npx expo install expo-router expo-dev-client react-native-safe-area-context react-native-screens
```

- [ ] **Step 4: Set the entry point and Android package**

In `package.json` set `"main": "expo-router/entry"`.

In `app.json`, inside `expo`, set:

```json
{
  "scheme": "moshgrid",
  "android": { "package": "com.moshgrid.app" },
  "plugins": ["expo-router"]
}
```

- [ ] **Step 5: Create the root layout**

```tsx
// src/app/_layout.tsx
import { Stack } from 'expo-router';

export default function RootLayout() {
  return <Stack screenOptions={{ headerTitleStyle: { fontWeight: '600' } }} />;
}
```

```tsx
// src/app/index.tsx
import { Link } from 'expo-router';
import { StyleSheet, Text, View } from 'react-native';

export default function Home() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>MoshGrid</Text>
      <Link href="/diagnostics" style={styles.link}>Diagnostics</Link>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16 },
  title: { fontSize: 28, fontWeight: '700' },
  link: { fontSize: 16, color: '#2563eb' },
});
```

- [ ] **Step 6: Build and run on the physical device**

Connect the phone by USB, then:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
adb devices
npx expo run:android --device
```

Expected: `adb devices` lists one device as `device` (not `unauthorized`). The app launches and shows "MoshGrid".

`ANDROID_HOME` was unset on this machine. Add both exports to `~/.zshrc` so this does not recur.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: scaffold Expo app with router and dev client"
```

---

## Task 2: Kotlin Expo Module — the native seam

Proves the PDF's Expo rejection wrong, and creates the module every later native capability lands in.

**Files:**
- Create: `modules/moshgrid-core/expo-module.config.json`
- Create: `modules/moshgrid-core/android/src/main/java/com/moshgrid/core/MoshgridCoreModule.kt`
- Create: `modules/moshgrid-core/index.ts`
- Create: `modules/moshgrid-core/src/MoshgridCore.types.ts`
- Test: `modules/moshgrid-core/android/src/test/java/com/moshgrid/core/MoshgridCoreModuleTest.kt`

**Interfaces:**
- Consumes: the Expo app from Task 1.
- Produces: `nativeInfo(): NativeInfo` where
  `NativeInfo = { moduleVersion: string; androidSdkInt: number; bleSupported: boolean; extendedAdvertisingSupported: boolean }`.
  Phase 3 relies on `bleSupported` and `extendedAdvertisingSupported`.

- [ ] **Step 1: Generate the module**

```bash
npx create-expo-module@latest --local moshgrid-core
```

Answer prompts so the directory is `modules/moshgrid-core` and the Android package is `com.moshgrid.core`.

- [ ] **Step 2: Write the failing Kotlin test**

```kotlin
// modules/moshgrid-core/android/src/test/java/com/moshgrid/core/MoshgridCoreModuleTest.kt
package com.moshgrid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshgridCoreModuleTest {

    @Test
    fun `nativeInfo reports a module version`() {
        val info = NativeInfoProvider.describe(
            sdkInt = 34,
            bleSupported = true,
            extendedAdvertisingSupported = false,
        )
        assertEquals("0.1.0", info.moduleVersion)
        assertEquals(34, info.androidSdkInt)
        assertTrue(info.bleSupported)
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
cd android && ./gradlew :moshgrid-core:testDebugUnitTest --tests '*MoshgridCoreModuleTest*'
```

Expected: FAIL — `Unresolved reference: NativeInfoProvider`.

- [ ] **Step 4: Write the minimal implementation**

```kotlin
// modules/moshgrid-core/android/src/main/java/com/moshgrid/core/NativeInfoProvider.kt
package com.moshgrid.core

data class NativeInfo(
    val moduleVersion: String,
    val androidSdkInt: Int,
    val bleSupported: Boolean,
    val extendedAdvertisingSupported: Boolean,
)

object NativeInfoProvider {
    const val MODULE_VERSION = "0.1.0"

    fun describe(
        sdkInt: Int,
        bleSupported: Boolean,
        extendedAdvertisingSupported: Boolean,
    ): NativeInfo = NativeInfo(
        moduleVersion = MODULE_VERSION,
        androidSdkInt = sdkInt,
        bleSupported = bleSupported,
        extendedAdvertisingSupported = extendedAdvertisingSupported,
    )
}
```

Splitting the pure logic out of the Expo module class is deliberate — it makes the logic testable under plain JUnit with no Android runtime.

- [ ] **Step 5: Run the test and watch it pass**

```bash
cd android && ./gradlew :moshgrid-core:testDebugUnitTest --tests '*MoshgridCoreModuleTest*'
```

Expected: PASS.

- [ ] **Step 6: Wire it into the Expo module**

```kotlin
// modules/moshgrid-core/android/src/main/java/com/moshgrid/core/MoshgridCoreModule.kt
package com.moshgrid.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class MoshgridCoreModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("MoshgridCore")

        Function("nativeInfo") {
            val context = appContext.reactContext
                ?: throw IllegalStateException("No react context")

            val bleSupported = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter

            val extended = adapter?.isLeExtendedAdvertisingSupported ?: false

            val info = NativeInfoProvider.describe(
                sdkInt = Build.VERSION.SDK_INT,
                bleSupported = bleSupported,
                extendedAdvertisingSupported = extended,
            )

            mapOf(
                "moduleVersion" to info.moduleVersion,
                "androidSdkInt" to info.androidSdkInt,
                "bleSupported" to info.bleSupported,
                "extendedAdvertisingSupported" to info.extendedAdvertisingSupported,
            )
        }
    }
}
```

`isLeExtendedAdvertisingSupported` is queried, never assumed. Defect 5 exists because the PDF assumed extended advertising was available.

- [ ] **Step 7: Expose it to TypeScript**

```typescript
// modules/moshgrid-core/src/MoshgridCore.types.ts
export type NativeInfo = {
  moduleVersion: string;
  androidSdkInt: number;
  bleSupported: boolean;
  extendedAdvertisingSupported: boolean;
};
```

```typescript
// modules/moshgrid-core/index.ts
import { requireNativeModule } from 'expo-modules-core';
import type { NativeInfo } from './src/MoshgridCore.types';

const MoshgridCore = requireNativeModule('MoshgridCore');

export function nativeInfo(): NativeInfo {
  return MoshgridCore.nativeInfo();
}

export type { NativeInfo };
```

- [ ] **Step 8: Rebuild and confirm on device**

```bash
npx expo run:android --device
```

Expected: build succeeds and the app launches. The module is loaded; Task 4 makes it visible.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add moshgrid-core Kotlin Expo module with native info probe"
```

---

## Task 3: vodozemac Kotlin binding — **GO/NO-GO GATE**

**This is the gate. If it fails, stop and re-plan before writing any more code.**

**Files:**
- Create: `crypto/Cargo.toml`, `crypto/src/lib.rs`, `crypto/src/moshgrid_crypto.udl`, `crypto/build.rs`
- Create: `crypto/uniffi.toml`
- Create: `scripts/build-crypto-android.sh`
- Test: `crypto/tests/roundtrip.rs`

**Interfaces:**
- Consumes: nothing.
- Produces, **on the Kotlin side**, class `MoshgridAccount` with
  `create(): MoshgridAccount`,
  `identityKeys(): IdentityKeys` (`{ ed25519: String, curve25519: String }`),
  `oneTimeKeys(count: UInt): List<String>`,
  `pickle(key: ByteArray): String`,
  `unpickle(pickle: String, key: ByteArray): MoshgridAccount`.
- Produces, **on the TypeScript side** (Step 11): `createAccount(): Account` and
  `unpickleAccount(pickle: string, key: Uint8Array): Account`, where `Account` is a live
  handle with `identityKeys()`, `oneTimeKeys(n)`, `sign(bytes)` and `pickle(key)`.
  Phase 1 builds identity and contacts directly on these.

**`pickleKey()` is not produced here.** It needs the Android Keystore and arrives in
Phase 1 Task 4. Phase 0 callers pass a key explicitly. An earlier draft had Phases 1 and 2
importing `pickleKey` from this module with a comment pointing at a "Task 0" that does not
exist (`DEFECTS.md` B4).

- [ ] **Step 1: Install the Rust toolchain**

Rust is not installed on this machine.

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
source "$HOME/.cargo/env"
rustc --version
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

Expected: `rustc --version` prints a version. All three targets install.

- [ ] **Step 2: Create the crate**

```bash
mkdir -p crypto && cd crypto && cargo init --lib --name moshgrid_crypto
```

```toml
# crypto/Cargo.toml
[package]
name = "moshgrid_crypto"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib", "lib"]
name = "moshgrid_crypto"

[dependencies]
vodozemac = "0.9"
uniffi = "0.28"
thiserror = "1"

[build-dependencies]
uniffi = { version = "0.28", features = ["build"] }

# Required for `cargo run --bin uniffi-bindgen`. Without this target the
# command fails with "no bin target named `uniffi-bindgen`".
[[bin]]
name = "uniffi-bindgen"
path = "uniffi-bindgen.rs"
```

```rust
// crypto/uniffi-bindgen.rs
fn main() {
    uniffi::uniffi_bindgen_main()
}
```

The `build` feature belongs only under `[build-dependencies]`; putting it under `[dependencies]` drags `uniffi_build` into the runtime graph for no benefit.

Confirm the resolved vodozemac version and record it:

```bash
cargo tree -p vodozemac | head -1
```

- [ ] **Step 3: Write the failing Rust round-trip test**

```rust
// crypto/tests/roundtrip.rs
use moshgrid_crypto::MoshgridAccount;

#[test]
fn account_exposes_both_identity_keys() {
    let account = MoshgridAccount::new();
    let keys = account.identity_keys();
    assert_eq!(keys.ed25519.len(), 43, "base64 Ed25519 key is 43 chars");
    assert_eq!(keys.curve25519.len(), 43, "base64 Curve25519 key is 43 chars");
}

#[test]
fn account_survives_pickle_round_trip() {
    let key = [7u8; 32];
    let account = MoshgridAccount::new();
    let before = account.identity_keys();

    let pickle = account.pickle(key.to_vec());
    let restored = MoshgridAccount::unpickle(pickle, key.to_vec()).expect("unpickle");

    assert_eq!(restored.identity_keys().ed25519, before.ed25519);
}

#[test]
fn unpickle_with_wrong_key_fails() {
    let account = MoshgridAccount::new();
    let pickle = account.pickle([1u8; 32].to_vec());
    assert!(MoshgridAccount::unpickle(pickle, [2u8; 32].to_vec()).is_err());
}
```

- [ ] **Step 4: Run and watch it fail**

```bash
cd crypto && cargo test
```

Expected: FAIL — `cannot find struct MoshgridAccount`.

- [ ] **Step 5: Define the UniFFI interface**

```
// crypto/src/moshgrid_crypto.udl
namespace moshgrid_crypto {};

dictionary IdentityKeys {
    string ed25519;
    string curve25519;
};

[Error]
enum CryptoError {
    "Unpickle",
    "InvalidKey",
};

interface MoshgridAccount {
    // Named `new` deliberately: UniFFI treats only a constructor named `new`
    // as primary, and emits it as a real Kotlin constructor. Any other name
    // becomes a companion-object function, so `MoshgridAccount()` would not
    // compile. `unpickle` is intentionally an alternate, called as
    // `MoshgridAccount.unpickle(...)`.
    constructor();

    [Name=unpickle, Throws=CryptoError]
    constructor(string pickle, bytes key);

    IdentityKeys identity_keys();
    sequence<string> one_time_keys(u32 count);
    [Throws=CryptoError] string pickle(bytes key);
    string sign(bytes message);
};
```

**`bytes`, not `sequence<u8>`.** UniFFI maps `sequence<u8>` to Kotlin `List<UByte>` and `bytes` to `ByteArray`. The Kotlin in Step 9 passes a `ByteArray`, so `sequence<u8>` would be a type error at the call site. The Rust side is `Vec<u8>` either way.

The Rust constructor must therefore be named `new`, not `create`:

```rust
impl MoshgridAccount {
    pub fn new() -> Self {
        Self { inner: Mutex::new(Account::new()) }
    }
}
```

Update the Rust tests in Step 3 to call `MoshgridAccount::new()` accordingly.

- [ ] **Step 6: Implement it**

```rust
// crypto/src/lib.rs
use std::sync::Mutex;
use vodozemac::olm::{Account, AccountPickle};

uniffi::include_scaffolding!("moshgrid_crypto");

#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("failed to unpickle account")]
    Unpickle,
    #[error("pickle key must be exactly 32 bytes")]
    InvalidKey,
}

pub struct IdentityKeys {
    pub ed25519: String,
    pub curve25519: String,
}

pub struct MoshgridAccount {
    inner: Mutex<Account>,
}

fn to_key_array(key: Vec<u8>) -> Result<[u8; 32], CryptoError> {
    key.try_into().map_err(|_| CryptoError::InvalidKey)
}

impl MoshgridAccount {
    pub fn create() -> Self {
        Self { inner: Mutex::new(Account::new()) }
    }

    pub fn unpickle(pickle: String, key: Vec<u8>) -> Result<Self, CryptoError> {
        let key = to_key_array(key)?;
        let pickle = AccountPickle::from_encrypted(&pickle, &key)
            .map_err(|_| CryptoError::Unpickle)?;
        Ok(Self { inner: Mutex::new(Account::from_pickle(pickle)) })
    }

    pub fn identity_keys(&self) -> IdentityKeys {
        let account = self.inner.lock().unwrap();
        let keys = account.identity_keys();
        IdentityKeys {
            ed25519: keys.ed25519.to_base64(),
            curve25519: keys.curve25519.to_base64(),
        }
    }

    pub fn one_time_keys(&self, count: u32) -> Vec<String> {
        let mut account = self.inner.lock().unwrap();
        account.generate_one_time_keys(count as usize);
        account.one_time_keys().values().map(|k| k.to_base64()).collect()
    }

    pub fn pickle(&self, key: Vec<u8>) -> Result<String, CryptoError> {
        let key = to_key_array(key)?;
        Ok(self.inner.lock().unwrap().pickle().encrypt(&key))
    }
}
```

**Why encrypted pickling is not optional.** `AccountPickle` serialises the Ed25519 and Curve25519 **secret** keys. Serialising it to plain JSON — as an earlier draft of this plan did — writes the device's private keys to disk in the clear, and makes `unpickle_with_wrong_key_fails` unpassable because any key would work. Verified signatures in vodozemac 0.9:

```rust
pub fn encrypt(self, pickle_key: &[u8; 32]) -> String
pub fn from_encrypted(ciphertext: &str, pickle_key: &[u8; 32]) -> Result<Self, PickleError>
```

Using these removes the need for `serde_json` and `base64_encode`/`base64_decode` entirely. Note `pickle` now returns `Result`, so the UDL must declare it `[Throws=CryptoError]`.

```rust
// crypto/build.rs
fn main() {
    uniffi::generate_scaffolding("src/moshgrid_crypto.udl").unwrap();
}
```

- [ ] **Step 7: Run the tests and watch them pass**

```bash
cd crypto && cargo test
```

Expected: all three PASS. `unpickle_with_wrong_key_fails` passing is the meaningful one — if it does not, pickles are not actually encrypted and the identity is stored in the clear.

- [ ] **Step 8: Cross-compile for Android and generate Kotlin bindings**

```bash
# scripts/build-crypto-android.sh
set -euo pipefail
cd "$(dirname "$0")/../crypto"

cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../modules/moshgrid-core/android/src/main/jniLibs \
  build --release

cargo run --features=uniffi/cli --bin uniffi-bindgen generate src/moshgrid_crypto.udl \
  --language kotlin \
  --out-dir ../modules/moshgrid-core/android/src/main/java
```

```bash
chmod +x scripts/build-crypto-android.sh && ./scripts/build-crypto-android.sh
```

Expected: `.so` files appear under `jniLibs/arm64-v8a/` and Kotlin bindings under `java/uniffi/moshgrid_crypto/`.

- [ ] **Step 9: Add a Kotlin-side self-test to the module**

```kotlin
// add to MoshgridCoreModule.kt definition block
Function("cryptoSelfTest") {
    val account = uniffi.moshgrid_crypto.MoshgridAccount()   // primary ctor, from `constructor()` named `new`
    val keys = account.identityKeys()
    val pickleKey = ByteArray(32) { 7 }
    val pickle = account.pickle(pickleKey)
    val restored = uniffi.moshgrid_crypto.MoshgridAccount.unpickle(pickle, pickleKey)

    mapOf(
        "ok" to (restored.identityKeys().ed25519 == keys.ed25519),
        "ed25519" to keys.ed25519,
        "curve25519" to keys.curve25519,
    )
}
```

- [ ] **Step 10: Run on the device — THE GATE**

```bash
npx expo run:android --device
```

Then call `cryptoSelfTest()` from the Diagnostics screen (Task 4).

**Expected: `ok: true` and two non-empty base64 keys, displayed on the phone.**

**If this fails:** stop. Do not continue to Phase 1. Fall back to the prebuilt AAR `org.matrix.rustcomponents:crypto-android` from Maven Central, which ships vodozemac through `matrix-sdk-crypto`. It carries Matrix's room and device model, which is more API surface than we want, so it is the fallback rather than the default — but it unblocks. Record which route was taken in `plans/README.md`.

- [ ] **Step 11: Expose the account to TypeScript as a live object**

This step exists because Phase 1 is written against a **stateful handle**:

```typescript
const account = createAccount();
const keys = account.identityKeys();
const sig  = account.sign(bytes);
const blob = account.pickle(key);
```

A plain `Function("createAccount")` cannot deliver that. Functions return serialisable
values across the bridge, and `MoshgridAccount` is a Kotlin object holding a pointer into
Rust-owned memory. Returning it from a `Function` either fails to convert or, worse,
returns a detached copy on which `sign()` uses the wrong key. **This was the real content
of `DEFECTS.md` B5** — the gap was recorded as a name mismatch and is actually an
architectural one.

The Expo Modules API has exactly the right primitive: a `SharedObject` exposed through the
`Class` DSL, with lifetime tied to the JS reference.

```kotlin
// modules/moshgrid-core/android/src/main/java/com/moshgrid/core/AccountRef.kt
package com.moshgrid.core

import expo.modules.kotlin.AppContext
import expo.modules.kotlin.sharedobjects.SharedObject
import uniffi.moshgrid_crypto.MoshgridAccount

class AccountRef(
    appContext: AppContext,
    val inner: MoshgridAccount,
) : SharedObject(appContext) {

    // The Rust side owns memory this Kotlin object only points at. Without this,
    // every account ever created leaks for the life of the process.
    override fun sharedObjectDidRelease() {
        inner.destroy()
    }
}
```

```kotlin
// add to MoshgridCoreModule.kt definition block
Class("Account", AccountRef::class) {
    Constructor {
        AccountRef(appContext, MoshgridAccount())
    }

    Function("identityKeys") { ref: AccountRef ->
        val k = ref.inner.identityKeys()
        mapOf("ed25519" to k.ed25519, "curve25519" to k.curve25519)
    }

    Function("oneTimeKeys") { ref: AccountRef, count: Int ->
        ref.inner.oneTimeKeys(count.toUInt())
    }

    Function("sign") { ref: AccountRef, message: ByteArray ->
        ref.inner.sign(message)
    }

    Function("pickle") { ref: AccountRef, key: ByteArray ->
        ref.inner.pickle(key)
    }

    StaticFunction("unpickle") { pickle: String, key: ByteArray ->
        AccountRef(appContext, MoshgridAccount.unpickle(pickle, key))
    }
}
```

**Verify two things here rather than assuming them**, because both are version-sensitive
and both fail quietly:

- **The UniFFI destructor name.** `destroy()` is the usual generated name for a Kotlin
  UniFFI object, but confirm it against the generated
  `java/uniffi/moshgrid_crypto/moshgrid_crypto.kt` from Step 8 before relying on it. If it
  is `close()` or the class is `AutoCloseable`, use that. A wrong name here compiles only
  if it happens to exist, and leaks silently if this override never runs.
- **The `SharedObject` constructor argument.** Verified against the installed
  `expo-modules-core`: the primary constructor takes `Runtime?`, with a secondary
  `constructor(appContext: AppContext)` — so `SharedObject(appContext)` is correct here,
  and the generated module template uses exactly that. Some Expo documentation says
  `RuntimeContext`; **that name does not exist in this version.** The compiler catches
  this one, but only after a full Gradle run, so it is worth getting right first time.

```typescript
// modules/moshgrid-core/src/MoshgridCore.types.ts — add
export type IdentityKeys = { ed25519: string; curve25519: string };

export interface Account {
  identityKeys(): IdentityKeys;
  oneTimeKeys(count: number): string[];
  /** Base64 Ed25519 signature over the given bytes. */
  sign(message: Uint8Array): string;
  pickle(key: Uint8Array): string;
}
```

```typescript
// modules/moshgrid-core/index.ts — add
export function createAccount(): Account {
  return new MoshgridCore.Account();
}

export function unpickleAccount(pickle: string, key: Uint8Array): Account {
  return MoshgridCore.Account.unpickle(pickle, key);
}

export type { Account, IdentityKeys };
```

Note the shape of the seam: `key` is a `Uint8Array` supplied by the caller. Phase 1 Task 4
adds `pickleKey()` and passes its result in. Phase 0 never touches the Keystore.

- [ ] **Step 12: Prove the handle is live, not a copy**

Extend the Diagnostics `cryptoSelfTest` (Task 4) to round-trip through TypeScript rather
than staying inside Kotlin:

```typescript
const a = createAccount();
const key = new Uint8Array(32).fill(7);
const before = a.identityKeys().ed25519;
const restored = unpickleAccount(a.pickle(key), key);

// Same identity after a pickle round-trip through JS.
const ok = restored.identityKeys().ed25519 === before;

// The handle is stateful: two calls on the SAME object must not mint new keys.
const stable = a.identityKeys().ed25519 === before;
```

**Both must be true on the phone.** `stable` is the one that catches a detached copy, and
it is the failure this whole step exists to prevent — a copy passes the round-trip test
and then signs with a key nobody else has.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: bind vodozemac to Kotlin via UniFFI and expose a live Account handle"
```

---

## Task 4: Diagnostics screen — the visible deliverable

**Files:**
- Create: `src/app/diagnostics.tsx`
- Create: `src/lib/version.ts`
- Test: `src/app/__tests__/diagnostics.test.tsx`

**Interfaces:**
- Consumes: `nativeInfo()` and `cryptoSelfTest()` from `modules/moshgrid-core`.
- Produces: a screen route at `/diagnostics`. Every later phase adds a row here rather than building its own debug UI.

- [ ] **Step 1: Add the test tooling**

```bash
npx expo install jest-expo jest
npm i -D @testing-library/react-native @types/jest
```

Add to `package.json`:

```json
{
  "scripts": {
    "test": "jest",
    "lint": "tsc --noEmit"
  },
  "jest": { "preset": "jest-expo" }
}
```

- [ ] **Step 2: Write the failing test**

```tsx
// src/app/__tests__/diagnostics.test.tsx
import { render, screen } from '@testing-library/react-native';
import Diagnostics from '../diagnostics';

jest.mock('../../../modules/moshgrid-core', () => ({
  nativeInfo: () => ({
    moduleVersion: '0.1.0',
    androidSdkInt: 34,
    bleSupported: true,
    extendedAdvertisingSupported: false,
  }),
  cryptoSelfTest: () => ({ ok: true, ed25519: 'AAA', curve25519: 'BBB' }),
}));

test('shows the native module version', async () => {
  render(<Diagnostics />);
  expect(await screen.findByText('0.1.0')).toBeTruthy();
});

test('shows crypto self-test as passing', async () => {
  render(<Diagnostics />);
  expect(await screen.findByText('PASS')).toBeTruthy();
});

test('reports extended advertising as unsupported when the device lacks it', async () => {
  render(<Diagnostics />);
  expect(await screen.findByText('Not supported')).toBeTruthy();
});
```

- [ ] **Step 3: Run and watch it fail**

```bash
npm test -- diagnostics
```

Expected: FAIL — cannot find module `../diagnostics`.

- [ ] **Step 4: Implement the screen**

```tsx
// src/app/diagnostics.tsx
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { nativeInfo, cryptoSelfTest } from '../../modules/moshgrid-core';

type Row = { label: string; value: string; ok?: boolean };

export default function Diagnostics() {
  const [rows, setRows] = useState<Row[]>([]);

  useEffect(() => {
    const info = nativeInfo();
    const crypto = cryptoSelfTest();
    setRows([
      { label: 'Native module', value: info.moduleVersion, ok: true },
      { label: 'Android SDK', value: String(info.androidSdkInt) },
      { label: 'BLE', value: info.bleSupported ? 'Supported' : 'Not supported', ok: info.bleSupported },
      {
        label: 'Extended advertising',
        value: info.extendedAdvertisingSupported ? 'Supported' : 'Not supported',
        ok: info.extendedAdvertisingSupported,
      },
      { label: 'Crypto self-test', value: crypto.ok ? 'PASS' : 'FAIL', ok: crypto.ok },
      { label: 'Ed25519 key', value: crypto.ed25519.slice(0, 16) + '…' },
    ]);
  }, []);

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.heading}>Diagnostics</Text>
      {rows.map((row) => (
        <View key={row.label} style={styles.row}>
          <Text style={styles.label}>{row.label}</Text>
          <Text style={[styles.value, row.ok === false && styles.bad, row.ok === true && styles.good]}>
            {row.value}
          </Text>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 20, gap: 4 },
  heading: { fontSize: 24, fontWeight: '700', marginBottom: 16 },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#d4d4d8' },
  label: { fontSize: 15, color: '#3f3f46' },
  value: { fontSize: 15, fontWeight: '600', fontVariant: ['tabular-nums'] },
  good: { color: '#15803d' },
  bad: { color: '#b91c1c' },
});
```

Note "Extended advertising: Not supported" is shown in red but is **not** a failure — it is information Phase 3 needs. Many mid-range Android phones lack it, which is exactly why defect 5 matters.

- [ ] **Step 5: Run the tests and watch them pass**

```bash
npm test -- diagnostics
```

Expected: 3 passed.

- [ ] **Step 6: Confirm on the device**

```bash
npx expo run:android --device
```

Tap through to Diagnostics. Expected: six rows, crypto self-test **PASS**.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add diagnostics screen showing native and crypto status"
```

---

## Task 5: CI and the licence gate

Makes "no AGPL" a property of the repository rather than something to remember. Defect 2 is the reason this task exists.

**Files:**
- Create: `scripts/check-licences.mjs`
- Create: `.github/workflows/ci.yml`
- Test: `scripts/__tests__/check-licences.test.mjs`

**Interfaces:**
- Consumes: `package.json`.
- Produces: `npm run check:licences`, exit 0 clean / exit 1 on any copyleft dependency.

- [ ] **Step 1: Write the failing test**

```javascript
// scripts/__tests__/check-licences.test.mjs
import { findForbidden } from '../check-licences.mjs';

test('flags AGPL packages', () => {
  const found = findForbidden({ 'libsignal-client': { licenses: 'AGPL-3.0-only' } });
  expect(found).toEqual([{ name: 'libsignal-client', licence: 'AGPL-3.0-only' }]);
});

test('flags GPL packages', () => {
  const found = findForbidden({ 'some-pkg': { licenses: 'GPL-3.0' } });
  expect(found).toHaveLength(1);
});

test('allows permissive licences', () => {
  const found = findForbidden({
    a: { licenses: 'MIT' },
    b: { licenses: 'Apache-2.0' },
    c: { licenses: 'BSD-3-Clause' },
    d: { licenses: 'ISC' },
  });
  expect(found).toEqual([]);
});

test('does not flag LGPL-adjacent names that are actually permissive', () => {
  expect(findForbidden({ 'gpl-parser': { licenses: 'MIT' } })).toEqual([]);
});
```

The last test matters: matching on the package **name** rather than its licence would produce false positives, and a noisy gate gets disabled.

- [ ] **Step 2: Run and watch it fail**

```bash
npm test -- check-licences
```

Expected: FAIL — cannot find module `../check-licences.mjs`.

- [ ] **Step 3: Implement the checker**

```javascript
// scripts/check-licences.mjs
const FORBIDDEN = [/\bAGPL\b/i, /\bGPL-\d/i, /\bGPL\b/i, /\bSSPL\b/i];

export function findForbidden(licenceMap) {
  const found = [];
  for (const [name, entry] of Object.entries(licenceMap)) {
    const licence = String(entry.licenses ?? '');
    if (/\bLGPL\b/i.test(licence)) continue;
    if (FORBIDDEN.some((re) => re.test(licence))) {
      found.push({ name, licence });
    }
  }
  return found;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { default: checker } = await import('license-checker-rseidelsohn');
  const licences = await new Promise((resolve, reject) =>
    checker.init({ start: process.cwd(), production: true }, (err, out) =>
      err ? reject(err) : resolve(out)),
  );
  const bad = findForbidden(licences);
  if (bad.length > 0) {
    console.error('Forbidden copyleft licences found:');
    for (const b of bad) console.error(`  ${b.name} — ${b.licence}`);
    console.error('\nSee docs/design.md §3.1. This is why libsignal was rejected.');
    process.exit(1);
  }
  console.log(`Licence check passed — ${Object.keys(licences).length} packages, none copyleft.`);
}
```

LGPL is skipped deliberately: dynamically-linked LGPL is compatible with a closed product, so flagging it would be a false positive. If an LGPL dependency is ever statically linked, that needs a human decision, not a regex.

- [ ] **Step 4: Run the tests and watch them pass**

```bash
npm i -D license-checker-rseidelsohn
npm test -- check-licences
```

Expected: 4 passed.

- [ ] **Step 5: Run it for real**

```bash
node scripts/check-licences.mjs
```

Expected: `Licence check passed — N packages, none copyleft.`

Add to `package.json`: `"check:licences": "node scripts/check-licences.mjs"`.

- [ ] **Step 6: Add CI**

```yaml
# .github/workflows/ci.yml
name: CI
on: [push, pull_request]

jobs:
  js:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: 'npm' }
      - run: npm ci
      - run: npm run lint
      - run: npm test
      - run: npm run check:licences

  rust:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo test --manifest-path crypto/Cargo.toml
      - run: cargo install cargo-deny --locked
      - run: cargo deny --manifest-path crypto/Cargo.toml --config crypto/deny.toml check licenses
```

```toml
# crypto/deny.toml
[licenses]
allow = ["MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Unicode-3.0", "CC0-1.0", "MPL-2.0"]
confidence-threshold = 0.9
```

Both sides of the tree are gated. The Rust half matters most — vodozemac's own dependencies are where an unexpected copyleft crate would appear.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "ci: add test, lint, and copyleft licence gates for JS and Rust"
```

---

## Exit gates

Every command must pass. These are commands, not judgement calls.

- [ ] `npm run lint` → exit 0
- [ ] `npm test` → all suites pass
- [ ] `npm run check:licences` → `Licence check passed`, exit 0
- [ ] `cargo test --manifest-path crypto/Cargo.toml` → 3 passed, including `unpickle_with_wrong_key_fails`
- [ ] `cargo deny --manifest-path crypto/Cargo.toml --config crypto/deny.toml check licenses` → exit 0
- [ ] `cd android && ./gradlew :moshgrid-core:testDebugUnitTest` → pass
- [ ] `npx expo run:android --device` → app launches on the physical phone
- [ ] **Diagnostics screen on the phone shows `Crypto self-test: PASS`** ← the gate
- [ ] **The self-test's `stable` check passes** — two `identityKeys()` calls on the same JS handle return the same key. This is what proves `createAccount()` returns a live object rather than a detached copy, and Phase 1 is unbuildable without it
- [ ] `AccountRef.sharedObjectDidRelease()` calls the destructor name the generated UniFFI bindings actually declare — read `java/uniffi/moshgrid_crypto/moshgrid_crypto.kt`, do not assume `destroy()`
- [ ] Toolchain versions recorded in `plans/README.md`: Expo SDK, vodozemac, Rust, Android SDK level of the test device

## Re-check before moving on

- [ ] Re-run the full gate list top to bottom on a clean checkout: `git clean -xdn` reviewed, then `npm ci && npm test && npm run check:licences`
- [ ] Confirm no `libsignal` anywhere: `grep -ri "libsignal" --include=package.json --include=Cargo.toml . ` → no matches outside `docs/` and `plans/`
- [ ] Confirm the phone actually ran the native module, not a JS mock — Diagnostics must show the device's real `androidSdkInt`, not `34` from the test fixture

## Updating the index

- [ ] Tick defect 2 and defect 7 in the [defect tracker](README.md#defect-regression-tracker)
- [ ] Set Phase 0 to `☑ Done` in [Progress](README.md#progress)
- [ ] Add the **Toolchain versions** section to `README.md` with the recorded values

## What Phase 1 relies on

| Produced here | Used by |
|---|---|
| `MoshgridAccount` Kotlin class | Phase 1 identity generation |
| `nativeInfo().extendedAdvertisingSupported` | Phase 3 advert strategy |
| Diagnostics screen route | Every later phase adds a row |
| Licence gate | Every later dependency addition |
