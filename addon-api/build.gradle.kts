// SPDX-License-Identifier: Apache-2.0

plugins {
    id("app.songyan.android.library")
}

android {
    namespace = "app.songyan.ime.addon"
    buildFeatures {
        buildConfig = false
        aidl = true
    }
    testOptions {
        // Protocol tests run on the JVM: every wire codec is a pure map, so no Android class is
        // needed to check the contract. Bundle adapters are covered by the instrumented tests.
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(libs.junit)
}
