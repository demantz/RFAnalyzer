import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.devtools)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.mantz_it.rfanalyzer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mantz_it.rfanalyzer"
        minSdk = 28
        targetSdk = 36
        versionCode = 20208
        versionName = "2.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FOSS", "false")
            resValue("string", "flavor_name", "Google Play")
            resValue("string", "app_name", "RF Analyzer")
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
            buildConfigField("boolean", "IS_FOSS", "true")
            resValue("string", "flavor_name", "FOSS")
            resValue("string", "app_name", "RF Analyzer (FOSS)")
        }
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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    useLibrary("android.test.base")
    useLibrary("android.test.runner")
    useLibrary("android.test.mock")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
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
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(project(":nativedsp"))
    implementation(project(":libhackrf"))
    implementation(project(":libairspy"))
    implementation(project(":libairspyhf"))
    implementation(project(":libhydrasdr"))
    implementation(project(":librtl433"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.datastore.core.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.compose.animation)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.security.crypto)
    implementation(libs.dagger.hilt)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.dagger.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    // Flavor-specific dependencies:
    add("playImplementation", libs.billing)  // "play" is the flavor
}
