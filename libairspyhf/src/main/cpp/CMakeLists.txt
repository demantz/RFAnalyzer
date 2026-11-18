cmake_minimum_required(VERSION 3.22.1)

project("libairspyhf")

# libusb is built by the :libusb module and imported via Prefab
find_package(libusb REQUIRED CONFIG)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")

add_library(${CMAKE_PROJECT_NAME} SHARED
        libairspyhf/airspyhf.c
        libairspyhf/iqbalancer.c
        airspyhf_device_native.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        libusb::usb
        android
        log)

# sample_handoff.h: the USB transfer -> Kotlin hand-off shared by all USB SDR drivers
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libusb/src/main/cpp/sample_handoff)
