plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mantz_it.libairspy"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                // This is necessary for ndk v27 to build 16KB Page-Size compatible libs:
                // https://developer.android.com/guide/practices/page-sizes
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":libusb"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}