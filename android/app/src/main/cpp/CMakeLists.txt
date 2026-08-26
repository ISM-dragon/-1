cmake_minimum_required(VERSION 3.22.1)
project(ondeviceai LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "ondeviceai supports Android arm64-v8a only; got ${ANDROID_ABI}")
endif()

# Build only the inference libraries. No whisper.cpp examples, CLI, server, tests,
# or network-dependent runtime component is included in the APK.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_WASM OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/whisper.cpp whisper.cpp-build EXCLUDE_FROM_ALL)

add_library(ondeviceai SHARED ondeviceai_jni.cpp)
target_include_directories(ondeviceai PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp/include
)
target_compile_options(ondeviceai PRIVATE -fvisibility=hidden -ffast-math)
target_link_libraries(ondeviceai PRIVATE whisper log android)
