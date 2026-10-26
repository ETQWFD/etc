#!/bin/bash
set -e
NDK=/home/user/android-sdk/ndk/28.2.13676358
SRC=/home/user/Doubao/chats/38445121917663234/rikkahub/llamajni
LLAMA=/tmp/llama.cpp
OUT=$SRC/out
mkdir -p $OUT
for ABI in armeabi-v7a arm64-v8a x86_64; do
  echo "===== BUILD $ABI ====="
  EXTRA=""
  if [ "$ABI" = "armeabi-v7a" ]; then EXTRA="-DGGML_LLAMAFILE=OFF"; fi
  cmake -S $SRC -B $OUT/$ABI \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_ARM_NEON=ON \
    -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_OPENMP=OFF -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF $EXTRA \
    -DCMAKE_BUILD_TYPE=Release > $OUT/$ABI-cmake.log 2>&1 || { echo "CMAKE FAIL $ABI"; tail -20 $OUT/$ABI-cmake.log; continue; }
  cmake --build $OUT/$ABI --target chat_jni -j 2 >> $OUT/$ABI-build.log 2>&1 || { echo "BUILD FAIL $ABI"; tail -25 $OUT/$ABI-build.log; continue; }
  find $OUT/$ABI -name "libchat_jni.so" -exec cp {} $SRC/libchat_jni-$ABI.so \;
  echo "DONE $ABI: $(ls -la $SRC/libchat_jni-$ABI.so 2>/dev/null | awk '{print $5}') bytes"
done
