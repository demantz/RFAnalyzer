plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.devtools)
    alias(libs.plugins.dagger.hilt)
}

android {
    namespace = "com.mantz_it.rfanalyzer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mantz_it.rfanalyzer"
        minSdk = 28
        targetSdk = 36
        versionCode = 20014
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    useLibrary("android.test.base")
    useLibrary("android.test.runner")
    useLibrary("android.test.mock")
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
    implementation(files("lib/hackrf_android.aar"))
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.datastore.core.android)
    implementation(libs.androidx.datastore.preferences)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.security.crypto)
    implementation(libs.billing)
    implementation(libs.dagger.hilt)
    ksp(libs.dagger.hilt.compiler)
}