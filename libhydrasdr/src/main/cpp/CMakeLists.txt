# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("libhydrasdr")

# Add the libusb module as a subdirectory
# This tells CMake to process the CMakeLists.txt in the libusb module.
add_subdirectory(../../../../libusb/src/main/cpp libusb_build_dir)

# Enable Compiler Optimization
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")

add_library(${CMAKE_PROJECT_NAME} SHARED
        libhydrasdr/hydrasdr.c
        libhydrasdr/iqconverter_float.c
        libhydrasdr/iqconverter_int16.c
        hydrasdr_device_native.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PUBLIC
        ${CMAKE_SOURCE_DIR}/../../../../libusb/src/main/cpp/include
)
target_link_libraries(${CMAKE_PROJECT_NAME}
        libusb
        android
        log)
