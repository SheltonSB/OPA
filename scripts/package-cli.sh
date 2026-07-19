#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
VERSION=${VERSION:-1.0.0}
PLATFORM=${PLATFORM:-$(uname -s | tr '[:upper:]' '[:lower:]')}
ARCH=${ARCH:-$(uname -m)}
OUT_DIR=${OUT_DIR:-"$ROOT/dist"}

case "$PLATFORM" in
  darwin|linux) ;;
  *) echo "Unsupported platform '$PLATFORM'; use darwin or linux." >&2; exit 20 ;;
esac

mvn --batch-mode --no-transfer-progress -f "$ROOT/pom.xml" -DskipTests package
mkdir -p "$OUT_DIR"
STAGE=$(mktemp -d "${TMPDIR:-/tmp}/opa-guard-package.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT INT TERM
mkdir -p "$STAGE/opa-policy-performance-guard-$VERSION/bin"
cp "$ROOT/target/opa-policy-performance-guard-$VERSION.jar" \
  "$STAGE/opa-policy-performance-guard-$VERSION/opa-policy-performance-guard-$VERSION.jar"
cp "$ROOT/opa-guard" "$STAGE/opa-policy-performance-guard-$VERSION/bin/opa-guard"
chmod +x "$STAGE/opa-policy-performance-guard-$VERSION/bin/opa-guard"
tar -czf "$OUT_DIR/opa-policy-performance-guard-$VERSION-$PLATFORM-$ARCH.tar.gz" \
  -C "$STAGE" "opa-policy-performance-guard-$VERSION"
echo "Created $OUT_DIR/opa-policy-performance-guard-$VERSION-$PLATFORM-$ARCH.tar.gz"
