#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../crypto"

cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../modules/moshgrid-core/android/src/main/jniLibs \
  build --release

cargo run --features=uniffi/cli --bin uniffi-bindgen generate src/moshgrid_crypto.udl \
  --language kotlin \
  --out-dir ../modules/moshgrid-core/android/src/main/java
