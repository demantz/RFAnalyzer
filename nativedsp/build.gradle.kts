plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mantz_it.nativedsp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
            version = "4.0.3"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.androidx.annotation)
    androidTestImplementation(libs.androidx.espresso.core)
}