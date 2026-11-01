plugins {
    id("app.songyan.android.test")
    alias(libs.plugins.baselineprofile)
}

// Generates the app's Baseline Profile by walking the journeys in ImeJourney.kt (shared with
// :benchmark). Run: ./gradlew :app:generateBaselineProfile
android {
    namespace = "app.songyan.ime.baselineprofile"
    sourceSets
        .getByName("main")
        .kotlin.directories
        .add("../benchmark/src/main/kotlin")
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
