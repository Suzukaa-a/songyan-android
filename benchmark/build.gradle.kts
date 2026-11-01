plugins {
    id("app.songyan.android.test")
    alias(libs.plugins.baselineprofile)
}

// Macrobenchmarks of the app's benchmarkRelease variant (release code, R8, optimized engine, not
// debuggable). Run: ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
android {
    // The namespace is also the test APK's package, so it must differ from the measured app's
    // benchmark id (app.songyan.ime.benchmark) or installing one replaces the other.
    namespace = "app.songyan.ime.benchmark.test"
    // An emulator only gives relative numbers; record which device a baseline came from.
    defaultConfig { testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR" }
}

baselineProfile {
    // This module measures; :baselineprofile generates.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
