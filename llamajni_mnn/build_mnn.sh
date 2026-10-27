#!/bin/bash
set -e
NDK=/home/user/android-sdk/ndk/28.2.13676358
LLAMA_INC=/tmp/mnn-src
TOP=/home/user/Doubao/chats/38445121917663234/rikkahub
mkdir -p out
for abi in armeabi-v7a arm64-v8a; do
  case $abi in
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi21; ARCH=arm;;
    arm64-v8a) TRIPLE=aarch64-linux-android21; ARCH=arm64;;
  esac
  CLANG=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/$TRIPLE-clang
  LIBDIR=$TOP/app/src/main/jniLibs/$abi
  mkdir -p out/$abi
  $CLANG -shared -fPIC -O2 -std=c++17 \
    -I$LLAMA_INC/include -I$LLAMA_INC/transformers/llm/engine/include \
    -I$LLAMA_INC/apps/Android/MnnLlmChat/app/src/main/cpp \
    -I$LLAMA_INC/apps/Android/MnnLlmChat/app/src/main/cpp/include \
    -L$LIBDIR -lllm -lMNN -lMNN_Express -llog -landroid \
    me_rerere_ai_local_MnnNative.cpp \
    -o out/$abi/libmnnchat_jni.so
  echo "DONE $abi: $(stat -c%s out/$abi/libmnnchat_jni.so) bytes"
done
