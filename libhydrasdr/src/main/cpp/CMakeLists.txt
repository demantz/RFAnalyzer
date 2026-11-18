cmake_minimum_required(VERSION 3.22.1)

project("libhydrasdr")

# libusb is built by the :libusb module and imported via Prefab
find_package(libusb REQUIRED CONFIG)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")

add_library(${CMAKE_PROJECT_NAME} SHARED
        libhydrasdr/hydrasdr.c
        libhydrasdr/iqconverter_float.c
        libhydrasdr/iqconverter_int16.c
        libhydrasdr/iqconverter.c
        libhydrasdr/iqconverter_float_opt33.c
        libhydrasdr/iqconverter_float_opt.c
        libhydrasdr/iqconverter_int16_opt33.c
        libhydrasdr/iqconverter_int16_opt.c
        libhydrasdr/hydrasdr_rfone.c
        libhydrasdr/iqconverter_decimator.c
        libhydrasdr/iqconverter_float_opt65.c
        libhydrasdr/iqconverter_float_opt_dec.c
        libhydrasdr/iqconverter_int16_opt65.c
        libhydrasdr/iqconverter_int16_opt_dec.c
        libhydrasdr/hydrasdr_shared.c
        libhydrasdr/iqconverter_float_opt83.c
        libhydrasdr/iqconverter_int16_opt83.c
        libhydrasdr/iqconverter_lut.c
        hydrasdr_device_native.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        libusb::usb
        android
        log)

# sample_handoff.h: the USB transfer -> Kotlin hand-off shared by all USB SDR drivers
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libusb/src/main/cpp/sample_handoff)
