cmake_minimum_required(VERSION 3.22.1)
project("lecturenotes")

add_subdirectory(whisper.cpp)

add_library(lecturenotes SHARED
    whisper_jni_bridge.cpp
)

target_link_libraries(lecturenotes
    whisper
    android
    log
)