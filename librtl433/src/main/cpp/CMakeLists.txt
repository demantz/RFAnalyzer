cmake_minimum_required(VERSION 3.22.1)
project("rfax_rtl433")

# =============================================================================
# librtl433 - rtl_433 decoder for RF Analyzer
# =============================================================================
# rtl_433 (https://github.com/merbanan/rtl_433, GPL-2.0-or-later) is vendored as
# a git submodule under ./rtl_433 and pinned to a release tag. See README.md.
#
# rtl_433 normally opens the SDR itself. RF Analyzer already owns the SDR, so
# we drive the decoder in "push" mode: rfax_bridge.c feeds interleaved IQ
# samples and receives decoded events as JSON.
#
# sdr_callback() in rtl_433.c is static, so we compile a *generated copy* of
# that one file with a small hook (rfax_feed_hook.inc) appended. This keeps the
# submodule pristine (no build-time patching) and makes the modification
# explicit here. Every other rtl_433 source is compiled unmodified.
# =============================================================================

set(RTL433_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/rtl_433)

if(NOT EXISTS ${RTL433_ROOT}/src/rtl_433.c)
    message(FATAL_ERROR
        "rtl_433 sources not found at ${RTL433_ROOT}. "
        "Run: git submodule update --init --recursive")
endif()

file(READ ${RTL433_ROOT}/src/rtl_433.c RTL433_MAIN_SRC)
file(READ ${CMAKE_CURRENT_SOURCE_DIR}/rfax_feed_hook.inc RFAX_HOOK)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/rtl_433_embedded.c
    "${RTL433_MAIN_SRC}\n${RFAX_HOOK}\n")

# All rtl_433 core and device sources, minus the original rtl_433.c (replaced
# by the generated copy above). CONFIGURE_DEPENDS re-globs when files change.
file(GLOB RTL433_CORE_SOURCES CONFIGURE_DEPENDS ${RTL433_ROOT}/src/*.c)
list(REMOVE_ITEM RTL433_CORE_SOURCES ${RTL433_ROOT}/src/rtl_433.c)
file(GLOB RTL433_DEVICE_SOURCES CONFIGURE_DEPENDS ${RTL433_ROOT}/src/devices/*.c)

add_library(${CMAKE_PROJECT_NAME} SHARED
    ${CMAKE_CURRENT_BINARY_DIR}/rtl_433_embedded.c
    ${RTL433_CORE_SOURCES}
    ${RTL433_DEVICE_SOURCES}
    rfax_bridge.c
    rfax_jni.cpp
    rfax_channel.c
    rfax_bionic_compat.c)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${RTL433_ROOT}/include
    ${RTL433_ROOT}
    ${RTL433_ROOT}/src
    ${CMAKE_CURRENT_SOURCE_DIR})

# rtl_433 is third-party: build it optimised but without warnings. Our own
# bridge files below keep the normal warning set.
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O3)
set_source_files_properties(
    ${CMAKE_CURRENT_BINARY_DIR}/rtl_433_embedded.c
    ${RTL433_CORE_SOURCES}
    ${RTL433_DEVICE_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w")

set_source_files_properties(
    rfax_bridge.c
    rfax_channel.c
    rfax_jni.cpp
    rfax_bionic_compat.c
    PROPERTIES COMPILE_OPTIONS "-Wall -Wextra -Wno-unused-parameter")

# Bionic does not implement pthread_cancel(), which the (unused) rtl_tcp output
# sink calls. Redirect it to a no-op shim. Applied on every platform so the
# build is identical everywhere; RF Analyzer never uses the rtl_tcp output.
set_source_files_properties(${RTL433_ROOT}/src/output_rtltcp.c PROPERTIES
    COMPILE_DEFINITIONS "pthread_cancel=rfax_pthread_cancel"
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/rfax_bionic_compat.h")

find_library(log-lib log)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE ${log-lib})
