cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY}
        dl)

add_library(termux SHARED
        src/main/jni/term.c)
target_compile_options(termux
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(termux
        PRIVATE
        ${LOG_LIBRARY})

# AV1 in software with dav1d on every core (Android's own dav1d uses half of them), for phones
# without an AV1 hardware decoder. Only 64-bit: arm64-v8a for phones, x86_64 for the CI emulator.
set(DAV1D_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/dav1d)
if((ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
        AND EXISTS ${DAV1D_DIR}/${ANDROID_ABI}/libdav1d.a)
    enable_language(CXX)
    add_library(dav1d_static STATIC IMPORTED)
    set_target_properties(dav1d_static PROPERTIES
            IMPORTED_LOCATION ${DAV1D_DIR}/${ANDROID_ABI}/libdav1d.a)
    add_library(dav1dJNI SHARED
            src/main/jni/av1/dav1d_jni.cc
            src/main/jni/av1/cpu_info.cc)
    target_include_directories(dav1dJNI PRIVATE ${DAV1D_DIR}/include)
    set_target_properties(dav1dJNI PROPERTIES
            CXX_STANDARD 17
            CXX_VISIBILITY_PRESET hidden
            VISIBILITY_INLINES_HIDDEN ON)
    target_compile_options(dav1dJNI PRIVATE -O2 -ffunction-sections -fdata-sections)
    target_link_libraries(dav1dJNI
            PRIVATE
            android
            ${LOG_LIBRARY}
            dav1d_static
            m
            -Wl,--gc-sections
            -Wl,--exclude-libs,ALL)
endif()
