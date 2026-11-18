plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mantz_it.libusb"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        externalNativeBuild {
            cmake {
                // 16 KB page sizes: NDK r28+ aligns the load segments by default, but newer Android
                // versions also require the RELRO segment to end on a 16 KB boundary.
                // https://developer.android.com/guide/practices/page-sizes
                arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,common-page-size=16384")
                // Pure C; without this Prefab refuses to hand a shared library with a
                // statically linked STL to the driver modules
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Exposes the library and its public header to the SDR driver modules
    buildFeatures {
        prefabPublishing = true
    }
    prefab {
        create("usb") {
            libraryName = "libusb1.0"
            headers = "src/main/cpp/libusb/libusb"
        }
    }
}

dependencies {
}
