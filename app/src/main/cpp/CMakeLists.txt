cmake_minimum_required(VERSION 3.22.1)
project("lecturenotes")

# C++ стандарт
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Оптимизация для release
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

# ARM NEON — критично для производительности Whisper на мобильных
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    add_definitions(-DGGML_USE_NEON)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon -mfloat-abi=softfp")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
endif()

# Whisper.cpp как подмодуль
add_subdirectory(whisper.cpp)

# Наша JNI-библиотека
add_library(lecturenotes SHARED
    whisper_jni_bridge.cpp
)

target_link_libraries(lecturenotes
    whisper
    android
    log
)