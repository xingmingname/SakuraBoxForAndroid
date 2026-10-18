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

GOMOBILE_PATCH="$SRC_ROOT/buildScript/lib/core/patches/gomobile-pinned-gobind.patch"
if git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply --check "$GOMOBILE_PATCH"; then
    git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply "$GOMOBILE_PATCH"
elif ! git -c "safe.directory=$GOMOBILE_SOURCE" -C "$GOMOBILE_SOURCE" apply --reverse --check "$GOMOBILE_PATCH"; then
    echo "Error: pinned gomobile patch does not match its source."
    exit 1
fi

export GOPATH="$GOMOBILE_GOPATH"
export GOBIN="$GOMOBILE_BIN"
export PATH="$GOMOBILE_BIN:$PATH"
BIN_SUFFIX=""
case "$OSTYPE" in
    cygwin*|msys*|win32*) BIN_SUFFIX=".exe" ;;
esac

if [ ! -x "$GOMOBILE_BIN/gomobile-matsuri$BIN_SUFFIX" ] || [ ! -x "$GOMOBILE_BIN/gobind-matsuri$BIN_SUFFIX" ]; then
    pushd "$GOMOBILE_SOURCE/cmd/gomobile" >/dev/null
    go install -v .
    popd >/dev/null
    pushd "$GOMOBILE_SOURCE/cmd/gobind" >/dev/null
    go install -v .
    popd >/dev/null
    mv -f "$GOMOBILE_BIN/gomobile$BIN_SUFFIX" "$GOMOBILE_BIN/gomobile-matsuri$BIN_SUFFIX"
    mv -f "$GOMOBILE_BIN/gobind$BIN_SUFFIX" "$GOMOBILE_BIN/gobind-matsuri$BIN_SUFFIX"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
