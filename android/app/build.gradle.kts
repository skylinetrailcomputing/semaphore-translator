plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin, applied on top of AGP 9's built-in Kotlin (its
    // version is pinned to the built-in Kotlin version in libs.versions.toml).
    alias(libs.plugins.compose.compiler)
}

// Stage just the two contract JSONs the decoder needs into a generated assets
// dir, copied straight from shared/ at build time. The shipping app must carry
// the contract on-device (it can't read ../shared/ at runtime), but this keeps
// shared/ the single source — no hand-maintained copy that could silently drift
// (the ADR 0001 Decision 2 rule). Surgical on purpose: pointing an asset dir at
// all of shared/ would also package the PNG fixtures, test vectors, and Python
// tools into the APK.
val sharedContractAssets = layout.buildDirectory.dir("generated/sharedContract/assets")

val copySharedContract by
    tasks.registering(Copy::class) {
        from(rootProject.file("../shared")) {
            include("semaphore_alphabet.json", "semaphore_config.json")
        }
        into(sharedContractAssets)
    }

// The asset-merge step (mergeDebugAssets / mergeReleaseAssets) must wait for the
// contract to be staged. Matched by name to avoid importing an AGP internal task
// type across version bumps.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copySharedContract) }

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

    buildFeatures { compose = true }

    sourceSets {
        // The shipping app loads the frozen contract from these staged assets at
        // runtime (`core/ContractLoader`); the dir is produced by copySharedContract.
        getByName("main").assets.directories.add(sharedContractAssets.get().asFile.path)
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
    implementation(libs.camera.view)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.kotlinx.coroutines.android)

    // Jetpack Compose live debug UI (#23). The BOM aligns the unversioned
    // artifacts; activity-compose carries setContent + the permission launcher.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Compose Navigation host for the Home → mode fork (#47).
    implementation(libs.androidx.navigation.compose)

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
