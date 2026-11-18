plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mantz_it.libairspyhf"
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
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        prefab = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":libusb")) // api: the app uses SampleStreamDevice/SampleBufferPool
    implementation(libs.androidx.annotation)
}