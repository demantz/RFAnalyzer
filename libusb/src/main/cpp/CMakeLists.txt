# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("libusb")

add_library(libusb_shared SHARED IMPORTED)

set_target_properties(libusb_shared PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb1.0.so"
)

add_library(libusb INTERFACE)

target_include_directories(libusb INTERFACE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_link_libraries(libusb INTERFACE libusb_shared)