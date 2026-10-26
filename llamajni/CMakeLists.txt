cmake_minimum_required(VERSION 3.14)
project(chat_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "")
set(LLAMA_BUILD_OPENMP OFF CACHE BOOL "")
set(LLAMA_CURL OFF CACHE BOOL "")
set(GGML_OPENMP OFF CACHE BOOL "")
set(GGML_NATIVE OFF CACHE BOOL "")

add_subdirectory(/tmp/llama.cpp llama)

add_library(chat_jni SHARED me_rerere_ai_local_LlamaNative.cpp)
target_link_libraries(chat_jni PRIVATE llama)
target_include_directories(chat_jni PRIVATE /tmp/llama.cpp/include)
