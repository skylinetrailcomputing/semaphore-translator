import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin, applied on top of AGP 9's built-in Kotlin (its
    // version is pinned to the built-in Kotlin version in libs.versions.toml).
    alias(libs.plugins.compose.compiler)
}

// Stage just the JSONs the app needs into a generated assets dir, copied
// straight from shared/ at build time: the two contract files the decoder needs,
// plus the first-launch disclaimer doc (#87). The shipping app must carry these
// on-device (it can't read ../shared/ at runtime), but this keeps shared/ the
// single source — no hand-maintained copy that could silently drift (the ADR
// 0001 Decision 2 rule). Surgical on purpose: pointing an asset dir at all of
// shared/ would also package the PNG fixtures, test vectors, and Python tools
// into the APK.
val sharedContractAssets = layout.buildDirectory.dir("generated/sharedContract/assets")

val copySharedContract by
    tasks.registering(Copy::class) {
        from(rootProject.file("../shared")) {
            include(
                "semaphore_alphabet.json",
                "semaphore_config.json",
                "disclaimer.json",
                "stock_passages.json",
            )
        }
        into(sharedContractAssets)
    }

// Everything that reads the staged contract dir must wait for copySharedContract:
// the asset-merge step (mergeDebugAssets / mergeReleaseAssets) AND the release
// lint-vital model task, which lints the raw asset source set directly. Gradle 9
// hard-fails the build (not just warns) on an undeclared dependency, and
// lint-vital only runs on the release path — which is why the debug smoke never
// surfaced it. Matched by name to avoid importing AGP-internal task types across
// version bumps.
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("lint", ignoreCase = true)
}
    .configureEach { dependsOn(copySharedContract) }

// Release signing (#84). Read from a gitignored `keystore.properties` at the
// android/ root (template: keystore.properties.example). Guarded so its absence
// is non-fatal: CI and outside contributors build debug with no keystore, and a
// release build with no keystore is simply unsigned (still exercises the full
// release graph — incl. lint-vital — which the #132 AAB break taught us debug
// smokes skip).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
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

    buildFeatures { compose = true }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Beta posture: keep R8/resource shrinking OFF for the F&F beta to
            // avoid ML Kit / CameraX keep-rule surprises. Revisit pre-wide-release.
            isMinifyEnabled = false
            // null when no keystore.properties ⇒ unsigned release (build still
            // verifies; you just can't upload it until the keystore exists).
            signingConfig = signingConfigs.findByName("release")
        }
    }

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
    implementation(libs.compose.material.icons.core)
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
