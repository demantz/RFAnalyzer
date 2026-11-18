cmake_minimum_required(VERSION 3.22.1)

project("libusb")

# Unmodified upstream sources (see GIT_COMMIT); file list and flags mirror
# upstream's android/jni/libusb.mk.
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

add_library(usb SHARED
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)

# libusb1.0.so: the library name Prefab publishes to the driver modules (libusb/build.gradle.kts)
set_target_properties(usb PROPERTIES OUTPUT_NAME "usb1.0")

target_include_directories(usb PRIVATE
        ${LIBUSB_ROOT}/android
        ${LIBUSB_ROOT}/libusb
        ${LIBUSB_ROOT}/libusb/os)

# -O2 also in debug builds: USB transfer handling sits in the sample path of every SDR driver
target_compile_options(usb PRIVATE -fvisibility=hidden -pthread -O2)

target_link_libraries(usb log)
