cmake_minimum_required(VERSION 3.22.1)
project("lecturenotes")

# Устанавливаем стандарты C++
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Пути к whisper.cpp
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Добавляем whisper.cpp как подпроект
# Отключаем ненужные компоненты для Android
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Добавляем whisper.cpp
add_subdirectory(${WHISPER_DIR} whisper_build)

# Наш JNI-библиотека
add_library(whisper_jni_bridge SHARED
    whisper_jni_bridge.cpp
)

# Include directories для whisper.h
target_include_directories(whisper_jni_bridge PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

# Линкуем с whisper и ggml
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