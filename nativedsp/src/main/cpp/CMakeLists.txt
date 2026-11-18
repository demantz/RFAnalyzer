cmake_minimum_required(VERSION 3.22.1)

project("nativedsp")

# Important to have -O3 for pffft:
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")
# nativedsp.cpp (spectrum FFT windowing/dB loop) - also optimized in debug builds, so on-device
# measurements with a debug build reflect the release cost.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_library(${CMAKE_PROJECT_NAME} SHARED
        nativedsp.cpp
        pffft.c
        rational_resampler.c
        iq_byte_mixer.c
        iq_byte_mixer_unsigned.c
        iq_word_mixer.c
        iq_float_mixer.c
        iq_byte_convert.c)

# The resampler, mixer and converter files must be bit-identical to their Kotlin fallbacks
# (RationalResampler, the IQ converters; checked by the on-device golden tests), so they must not
# inherit the global -ffast-math (permits reassociation/non-IEEE reductions) or the compiler's
# default fp-contract=on (which would fuse a += b*c into a single-rounding FMA - something
# Kotlin/JVM float arithmetic never does implicitly). -O3 alone is safe.
set_source_files_properties(rational_resampler.c iq_byte_mixer.c iq_byte_mixer_unsigned.c iq_word_mixer.c iq_float_mixer.c iq_byte_convert.c PROPERTIES
        COMPILE_OPTIONS "-fno-fast-math;-ffp-contract=off")

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
