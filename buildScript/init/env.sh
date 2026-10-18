#!/bin/bash

source buildScript/init/env_ndk.sh

if [[ "$OSTYPE" =~ ^darwin ]]; then
  export SRC_ROOT=$PWD
else
  export SRC_ROOT=$(realpath .)
fi

case "$OSTYPE" in
  cygwin*|msys*|win32*)
    DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin
    CLANG_SUFFIX=.cmd
    STRIP_TOOL=$DEPS/llvm-strip.exe
    ;;
  darwin*)
    if [[ "$(uname -m)" == arm64 ]]; then
      NDK_HOST=darwin-arm64
    else
      NDK_HOST=darwin-x86_64
    fi
    DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin
    CLANG_SUFFIX=
    STRIP_TOOL=$DEPS/llvm-strip
    ;;
  *)
    DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
    CLANG_SUFFIX=
    STRIP_TOOL=$DEPS/llvm-strip
    ;;
esac

export ANDROID_ARM_CC=$DEPS/armv7a-linux-androideabi21-clang$CLANG_SUFFIX
export ANDROID_ARM_CXX=$DEPS/armv7a-linux-androideabi21-clang++$CLANG_SUFFIX
export ANDROID_ARM_CC_21=$DEPS/armv7a-linux-androideabi21-clang$CLANG_SUFFIX
export ANDROID_ARM_CXX_21=$DEPS/armv7a-linux-androideabi21-clang++$CLANG_SUFFIX
export ANDROID_ARM_STRIP=$STRIP_TOOL

export ANDROID_ARM64_CC=$DEPS/aarch64-linux-android21-clang$CLANG_SUFFIX
export ANDROID_ARM64_CXX=$DEPS/aarch64-linux-android21-clang++$CLANG_SUFFIX
export ANDROID_ARM64_STRIP=$STRIP_TOOL

export ANDROID_X86_CC=$DEPS/i686-linux-android21-clang$CLANG_SUFFIX
export ANDROID_X86_CXX=$DEPS/i686-linux-android21-clang++$CLANG_SUFFIX
export ANDROID_X86_CC_21=$DEPS/i686-linux-android21-clang$CLANG_SUFFIX
export ANDROID_X86_CXX_21=$DEPS/i686-linux-android21-clang++$CLANG_SUFFIX
export ANDROID_X86_STRIP=$STRIP_TOOL

export ANDROID_X86_64_CC=$DEPS/x86_64-linux-android21-clang$CLANG_SUFFIX
export ANDROID_X86_64_CXX=$DEPS/x86_64-linux-android21-clang++$CLANG_SUFFIX
export ANDROID_X86_64_STRIP=$STRIP_TOOL
