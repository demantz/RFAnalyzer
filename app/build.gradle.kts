plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.devtools)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.mantz_it.rfanalyzer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mantz_it.rfanalyzer"
        minSdk = 28
        targetSdk = 37
        versionCode = 20301   // major·10000 + minor·100 + number of the upload (2.3beta1 = 20301)
        versionName = "2.3beta1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FOSS", "false")
            // Digital features shown without the extension installed (DigitalFeatures): false in
            // RF Analyzer 2.3, while the extension is in its closed test; true from 2.4 on.
            buildConfigField("boolean", "DIGITAL_LAUNCHED", "false")
            resValue("string", "flavor_name", "Google Play")
            resValue("string", "app_name", "RF Analyzer")
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
            buildConfigField("boolean", "IS_FOSS", "true")
            buildConfigField("boolean", "DIGITAL_LAUNCHED", "false") // never shown in this build anyway
            resValue("string", "flavor_name", "FOSS")
            resValue("string", "app_name", "RF Analyzer (FOSS)")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            // The bundle carries the native libraries' symbol tables, so Play Console shows
            // native crashes with function names.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // Release code generation (no CheckJNI, no debuggable interpreter overhead) for on-device
        // performance measurements: profileable by simpleperf, installable over the debug build
        // (same key), with the debug build's adb control receiver (src/debug).
        create("profile") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("profile") {
        kotlin.directories.add("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Build static site from /docs and make it available in the app's assets:
tasks.register<Exec>("generateDocs") {
    description = "Builds the MkDocs static website"
    group = "documentation"
    workingDir = file("$projectDir/../")
    commandLine("mkdocs", "build", "--clean", "--no-directory-urls", "--site-dir", "build_site")
}
tasks.register<Copy>("copyDocsToAssets") {
    dependsOn("generateDocs")
    from("$projectDir/../build_site") {
        exclude("sitemap.xml.gz")  // Exclude the gz file as it causes problems when merging assets
    }
    into("$projectDir/src/main/assets/docs")
}
tasks.named("preBuild") {
    dependsOn("copyDocsToAssets")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(project(":nativedsp"))
    implementation(project(":libhackrf"))
    implementation(project(":libairspy"))
    implementation(project(":libairspyhf"))
    implementation(project(":libhydrasdr"))
    implementation(project(":librtlsdr"))
    implementation(project(":ipc"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.datastore.core.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.compose.animation)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.dagger.hilt)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.dagger.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.compose.material3)
    implementation(libs.maplibre.compose.runtime.opengl)
    implementation(libs.okhttp)

    // Flavor-specific dependencies:
    add("playImplementation", libs.billing.ktx)
}
