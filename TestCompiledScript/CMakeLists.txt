cmake_minimum_required(VERSION 3.10.2)
project(TestCompiledScript C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/imgui)
set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src)

include_directories(
    ${SRC_DIR}
    ${IMGUI_DIR}
)

add_executable(test_script
    ${SRC_DIR}/syscall_probe.c
    ${SRC_DIR}/main.cpp
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
)

target_compile_options(test_script PRIVATE
    -O3
    -fPIE
    -Wall
)

target_link_options(test_script PRIVATE
    -pie
    -static-libstdc++
)
