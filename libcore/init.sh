#!/bin/bash
set -e

LIBCORE_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_ROOT="${SRC_ROOT:-$(cd "$LIBCORE_DIR/.." && pwd)}"
source "$SRC_ROOT/buildScript/lib/core/get_source_env.sh"

DEPS_ROOT="$SRC_ROOT/.build-deps"
GOMOBILE_SOURCE="$DEPS_ROOT/gomobile"
GOMOBILE_BIN="${GOMOBILE_BIN:-$DEPS_ROOT/bin}"
GOMOBILE_GOPATH="${GOMOBILE_GOPATH:-$DEPS_ROOT/gopath}"
mkdir -p "$GOMOBILE_BIN" "$GOMOBILE_GOPATH"

if [ ! -d "$GOMOBILE_SOURCE/.git" ]; then
    echo "Error: pinned gomobile source is missing; run buildScript/lib/core/get_source.sh first."
    exit 1
fi

git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" rev-parse --verify "$COMMIT_GOMOBILE^{commit}" >/dev/null
if [ "$(git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" rev-parse HEAD)" != "$COMMIT_GOMOBILE" ]; then
    echo "Error: gomobile source is not at the pinned commit $COMMIT_GOMOBILE."
    exit 1
fi

PATCH_DIR="$SRC_ROOT/buildScript/lib/core/patches"
for PATCH_NAME in gomobile-pinned-gobind.patch gomobile-private-paths.patch; do
    GOMOBILE_PATCH="$PATCH_DIR/$PATCH_NAME"
    if git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply --check "$GOMOBILE_PATCH"; then
        git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply "$GOMOBILE_PATCH"
    elif ! git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply --reverse --check "$GOMOBILE_PATCH"; then
        echo "Error: gomobile patch $PATCH_NAME does not match its source."
        exit 1
    fi
done
# An existing tool built before a patch change must not bypass that fix.
PATCH_HASH=$(cat "$PATCH_DIR/gomobile-pinned-gobind.patch" "$PATCH_DIR/gomobile-private-paths.patch" | git hash-object --stdin)
PATCH_STAMP="$GOMOBILE_BIN/.sakurabox-gomobile-patches.id"

export GOPATH="$GOMOBILE_GOPATH"
export GOBIN="$GOMOBILE_BIN"
export PATH="$GOMOBILE_BIN:$PATH"
BIN_SUFFIX=""
case "$OSTYPE" in
    cygwin*|msys*|win32*) BIN_SUFFIX=".exe" ;;
esac

if [ ! -x "$GOMOBILE_BIN/gomobile-matsuri$BIN_SUFFIX" ] || [ ! -x "$GOMOBILE_BIN/gobind-matsuri$BIN_SUFFIX" ] || [ ! -f "$PATCH_STAMP" ] || [ "$(cat "$PATCH_STAMP")" != "$PATCH_HASH" ]; then
    pushd "$GOMOBILE_SOURCE/cmd/gomobile" >/dev/null
    go install -v .
    popd >/dev/null
    pushd "$GOMOBILE_SOURCE/cmd/gobind" >/dev/null
    go install -v .
    popd >/dev/null
    mv -f "$GOMOBILE_BIN/gomobile$BIN_SUFFIX" "$GOMOBILE_BIN/gomobile-matsuri$BIN_SUFFIX"
    mv -f "$GOMOBILE_BIN/gobind$BIN_SUFFIX" "$GOMOBILE_BIN/gobind-matsuri$BIN_SUFFIX"
    printf '%s\n' "$PATCH_HASH" > "$PATCH_STAMP"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
