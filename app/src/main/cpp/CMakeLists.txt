cmake_minimum_required(VERSION 3.22)
project("lecturenotes")

add_compile_options(-O3)

add_subdirectory(whisper.cpp)

add_library(lecturenotes SHARED
    native-lib.cpp
)

target_link_libraries(lecturenotes
    whisper
    ggml
    android
    log
)

target_include_directories(lecturenotes PRIVATE
    whisper.cpp
)
