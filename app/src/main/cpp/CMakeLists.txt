# OpenClip M1 — libopenclip.so builds the JNI shim from ExternalC.cpp only;
# engine sources are consumed via their public headers from the engine lane
# (see ARCHITECTURE.md). Engine core is built into the same library once CI
# wires the engine Makefile into a static archive (M2); M1 compiles engine
# sources by path so a single .so is shipped.
project(openclip LANGUAGES CXX)
cmake_minimum_required(VERSION 3.22)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine)
set(ENGINE_SRC_DIR ${ENGINE_DIR}/src)

file(GLOB ENGINE_SOURCES
     ${ENGINE_SRC_DIR}/blend/*.cpp
     ${ENGINE_SRC_DIR}/brush/*.cpp
     ${ENGINE_SRC_DIR}/common/*.cpp
     ${ENGINE_SRC_DIR}/io/*.cpp
     ${ENGINE_SRC_DIR}/raster/*.cpp
     ${ENGINE_SRC_DIR}/undo/*.cpp)

add_library(openclip SHARED
    ExternalC.cpp
    ${ENGINE_SOURCES})

target_compile_options(openclip PRIVATE -O2 -Wall -Wextra)
target_include_directories(openclip PRIVATE
    ${ENGINE_DIR}/include
    ${ENGINE_DIR}/third_party
    ${ENGINE_SRC_DIR}/io)
find_library(log-lib log)
target_link_libraries(openclip PRIVATE z ${log-lib})
