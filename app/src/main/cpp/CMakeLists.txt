cmake_minimum_required(VERSION 3.22.1)

project("neural_trainer_jni")

# C++20 for modern compiler optimizations & SIMD auto-vectorization
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# High-performance native optimization flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -funroll-loops -Wall -Wextra")

# For ARM64 NEON optimization
if(${ANDROID_ABI} MATCHES "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")
elseif(${ANDROID_ABI} MATCHES "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
endif()

add_library(
    neural_trainer_jni
    SHARED
    neural_trainer.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    neural_trainer_jni
    ${log-lib}
)

# 16 KB page size alignment for Android 15+ / 16 KB kernel architecture compatibility
target_link_options(
    neural_trainer_jni
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
