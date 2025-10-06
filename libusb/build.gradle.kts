plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mantz_it.libusb"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
}