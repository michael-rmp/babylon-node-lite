#!/bin/sh
# Builds the installation package of the lite validator (on a build machine, not on the validator).
#   docs/lite-validator/ops/build-lite-package.sh <output-directory>
# Result: lite-validator-<version>.tar.gz and .sha256
set -eu
REPO=$(cd "$(dirname "$0")/../../.." && pwd -P)
OUT=${1:?output directory missing}
cd "$REPO"
[ -z "$(git status --porcelain -- core core-rust core-rust-bridge common keygen)" ] || {
  echo "ERROR: there are uncommitted changes in the code." >&2; exit 1; }
COMMIT=$(git rev-parse --short=10 HEAD)
VERSION="1.4.0.0-lite.$COMMIT"
NAME="lite-validator-$VERSION"
[ ! -f "$HOME/.cargo/env" ] || . "$HOME/.cargo/env"
command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo not found; install Rust with rustup (see INSTALL.md)" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "ERROR: java not found; install JDK 17 (see INSTALL.md)" >&2; exit 1; }

echo "== Rust library (release)"
(cd core-rust && cargo build --release -p jni-export)
echo "== Java (jars and start scripts)"
SKIP_NATIVE_RUST_BUILD=TRUE ./gradlew :core:installDist :core:liteStartScripts --console=plain -q

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
P="$STAGE/$NAME"
mkdir -p "$P/bin" "$P/lib/native"
cp core/build/install/core/lib/*.jar "$P/lib/"
cp core/build/liteScripts/lite-validator core/build/install/core/bin/keygen "$P/bin/"
cp core-rust/target/release/libcorerust.so "$P/lib/native/"
cp docs/lite-validator/ops/package/install.sh docs/lite-validator/ops/package/mainnet.config.lite-lines.example docs/lite-validator/ops/package/mainnet.config.example "$P/"
cp LICENSE NOTICE "$P/"   # Radix License 4A/4D: the license and the notices go with every copy
mkdir -p "$P/watchdog"
cp docs/lite-validator/ops/radix-lite-watchdog.* "$P/watchdog/"
# Safety net: the package must not contain an entry point for a standard node.
[ ! -e "$P/bin/core" ] || { echo "ERROR: bin/core in the package" >&2; exit 1; }
grep -q 'com.radixdlt.lite.LiteValidatorApplication' "$P/bin/lite-validator"
cat > "$P/VERSION" <<V
version=$VERSION
base=v1.4.0.0
commit=$(git rev-parse HEAD)
built=$(date -u +%FT%TZ)
libcorerust_sha256=$(sha256sum "$P/lib/native/libcorerust.so" | cut -d' ' -f1)
V
chmod -R go-w "$P"; chmod 755 "$P/bin/"* "$P/install.sh" "$P/watchdog/radix-lite-watchdog.sh"
mkdir -p "$OUT"
tar -C "$STAGE" --owner=0 --group=0 -czf "$OUT/$NAME.tar.gz" "$NAME"
(cd "$OUT" && sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256" && cat "$NAME.tar.gz.sha256" && ls -la "$NAME.tar.gz")
