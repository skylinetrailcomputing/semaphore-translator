plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.skylinetrailcomputing.semaphore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skylinetrailcomputing.semaphore"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.gson)
    testImplementation(libs.junit)
}
