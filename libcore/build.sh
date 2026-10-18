#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh
SRC_ROOT="${SRC_ROOT:-$(cd .. && pwd)}"

BUILD="${BUILD_DIR:-.build-sing-box-1.14.3}"
GOMOBILE_BIN="${GOMOBILE_BIN:-$SRC_ROOT/.build-deps/bin}"
OUTPUT_DIR="${OUTPUT_DIR:-$SRC_ROOT/.build-deps/outputs}"
OUTPUT_AAR="${OUTPUT_AAR:-$OUTPUT_DIR/libcore-sing-box-1.14.3-sakura-sr01.aar}"
APP_AAR="${APP_AAR:-$SRC_ROOT/app/libs/libcore-sing-box-1.14.3-sakura-sr01.aar}"
if [ -e "$OUTPUT_AAR" ] || [ -e "$APP_AAR" ]; then
  echo "Refusing to overwrite an existing AAR. Set new OUTPUT_AAR and APP_AAR paths."
  exit 1
fi
mkdir -p "$BUILD" "$OUTPUT_DIR" "$SRC_ROOT/app/libs"

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
export PATH="$GOMOBILE_BIN:$PATH"
export CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
"$GOMOBILE_BIN/gomobile-matsuri" bind -v -androidapi 21 -cache "$(realpath "$BUILD")" -o "$OUTPUT_AAR" -trimpath -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
if [ ! -s "$OUTPUT_AAR" ]; then
  echo "Error: gomobile bind did not produce $OUTPUT_AAR"
  exit 1
fi
mkdir -p "$(dirname "$APP_AAR")"
cp -n "$OUTPUT_AAR" "$APP_AAR"
echo ">> generated $(realpath "$OUTPUT_AAR")"
echo ">> installed $(realpath "$APP_AAR")"
