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

# ARM NEON — только для ARMv7, на ARM64 включён по умолчанию
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    add_definitions(-DGGML_USE_NEON)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon -mfloat-abi=softfp")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -mfloat-abi=softfp")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_definitions(-DGGML_USE_NEON)
    # На ARM64 NEON доступен без дополнительных флагов
endif()

# Пути к whisper.cpp
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Отключаем ненужные компоненты для Android
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Добавляем whisper.cpp
add_subdirectory(${WHISPER_DIR} whisper_build)

# Наша JNI-библиотека
add_library(whisper_jni_bridge SHARED
    whisper_jni_bridge.cpp
)

# Include directories для whisper.h
target_include_directories(whisper_jni_bridge PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

# Линкуем с whisper, ggml и системными библиотеками
target_link_libraries(whisper_jni_bridge
    whisper
    ggml
    android
    log
)

# Флаги компиляции для оптимизации
target_compile_options(whisper_jni_bridge PRIVATE
    -O3
    -fvisibility=hidden
)