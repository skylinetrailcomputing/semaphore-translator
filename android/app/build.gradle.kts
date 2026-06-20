plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.skylinetrailcomputing.semaphore"
    // CameraX 1.6.x compiles against API 36; targetSdk stays 35 (compile-only
    // bump, no runtime-behavior opt-in). See docs/adr/0002.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skylinetrailcomputing.semaphore"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The native-fixture invariant DSL + DTOs are shared verbatim by the
        // local-JVM Layer-1 test and the instrumented Layer-2 calibration (#22),
        // so both layers evaluate `invariants.json` identically — one source.
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
        // Layer-2 reads the canonical fixtures (PNGs + invariants.json) straight
        // from shared/, packaged as androidTest assets — no copy/symlink (the #14
        // single-source rule; rootProject is android/, so ../shared reaches repo).
        getByName("androidTest").assets.directories.add(
            rootProject.file("../shared/native_fixtures").path
        )
    }
}

dependencies {
    // Shipping app: CameraX capture + ML Kit pose + coroutines (the Flow surface).
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.kotlinx.coroutines.android)

    // Gson and the JSON contract DTOs are used only by the parity / fixture
    // harnesses, so they stay out of the shipping app (the decoder takes plain
    // values).
    testImplementation(libs.gson)
    testImplementation(libs.junit)

    // Instrumented Layer-2 calibration (the repo's first androidTest).
    androidTestImplementation(libs.gson)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
