#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
DEPS_ROOT="$SRC_ROOT/.build-deps"
mkdir -p "$DEPS_ROOT"

checkout_pinned_source() {
  local source_dir="$1"
  local source_url="$2"
  local source_commit="$3"

  if [ ! -d "$source_dir/.git" ]; then
    if [ -e "$source_dir" ]; then
      echo "Error: $source_dir exists but is not a Git checkout; refusing to replace it."
      return 1
    fi
    git -c http.sslBackend=openssl clone --no-checkout "$source_url" "$source_dir"
  fi

  git_in_source() {
    git -c "safe.directory=$source_dir" -C "$source_dir" "$@"
  }

  local current_commit
  current_commit=$(git_in_source rev-parse HEAD 2>/dev/null || true)
  local only_git_metadata=1
  local entry
  for entry in "$source_dir"/* "$source_dir"/.[!.]*; do
    [ -e "$entry" ] || continue
    if [ "$(basename "$entry")" != ".git" ]; then
      only_git_metadata=0
      break
    fi
  done
  if [ "$current_commit" != "$source_commit" ] || [ "$only_git_metadata" -eq 1 ]; then
    if [ "$only_git_metadata" -eq 0 ] && [ -n "$(git_in_source status --porcelain --untracked-files=all)" ]; then
      echo "Error: refusing to switch modified dependency checkout $source_dir."
      return 1
    fi
    git_in_source checkout --detach "$source_commit"
  fi
}

SING_BOX_DIR="$DEPS_ROOT/sing-box"
LIBNEKO_DIR="$DEPS_ROOT/libneko"
GOMOBILE_DIR="$DEPS_ROOT/gomobile"

checkout_pinned_source "$SING_BOX_DIR" https://github.com/SagerNet/sing-box.git "$COMMIT_SING_BOX"
checkout_pinned_source "$LIBNEKO_DIR" https://github.com/MatsuriDayo/libneko.git "$COMMIT_LIBNEKO"
checkout_pinned_source "$GOMOBILE_DIR" https://github.com/MatsuriDayo/gomobile.git "$COMMIT_GOMOBILE"

pushd "$SING_BOX_DIR" >/dev/null
PATCH_FILE="$SRC_ROOT/buildScript/lib/core/patches/sing-box-1.14.3.patch"
if git -c "safe.directory=$SING_BOX_DIR" apply --check "$PATCH_FILE"; then
  git -c "safe.directory=$SING_BOX_DIR" apply "$PATCH_FILE"
elif git -c "safe.directory=$SING_BOX_DIR" apply --reverse --check "$PATCH_FILE"; then
  echo "sing-box 1.14.3 compatibility patch is already applied."
else
  echo "Error: sing-box 1.14.3 compatibility patch does not match this source."
  exit 1
fi
for package in boxapi nekoutils; do
  mkdir -p "$package"
  for source_file in "$SRC_ROOT/buildScript/lib/core/patches/$package"/*.go; do
    target_file="$package/$(basename "$source_file")"
    if [ -e "$target_file" ]; then
      if ! cmp -s "$source_file" "$target_file"; then
        echo "Error: refusing to overwrite customized $target_file"
        exit 1
      fi
    else
      cp "$source_file" "$target_file"
    fi
  done
done
popd >/dev/null

pushd "$LIBNEKO_DIR" >/dev/null
LIBNEKO_PATCH="$SRC_ROOT/buildScript/lib/core/patches/libneko-protect-ack.patch"
if git -c "safe.directory=$LIBNEKO_DIR" apply --check "$LIBNEKO_PATCH"; then
  git -c "safe.directory=$LIBNEKO_DIR" apply "$LIBNEKO_PATCH"
elif ! git -c "safe.directory=$LIBNEKO_DIR" apply --reverse --check "$LIBNEKO_PATCH"; then
  echo "Error: libneko protection patch does not match the pinned source."
  exit 1
fi
popd >/dev/null

pushd "$GOMOBILE_DIR" >/dev/null
for PATCH_NAME in gomobile-pinned-gobind.patch gomobile-private-paths.patch; do
  GOMOBILE_PATCH="$SRC_ROOT/buildScript/lib/core/patches/$PATCH_NAME"
  if git -c "safe.directory=$GOMOBILE_DIR" apply --check "$GOMOBILE_PATCH"; then
    git -c "safe.directory=$GOMOBILE_DIR" apply "$GOMOBILE_PATCH"
  elif git -c "safe.directory=$GOMOBILE_DIR" apply --reverse --check "$GOMOBILE_PATCH"; then
    echo "Gomobile patch $PATCH_NAME is already applied."
  else
    echo "Error: gomobile patch $PATCH_NAME does not match the source checkout."
    exit 1
  fi
done
popd >/dev/null
