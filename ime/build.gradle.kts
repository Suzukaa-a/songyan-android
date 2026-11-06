plugins {
    id("app.songyan.android.library")
    id("app.songyan.android.compose")
}

android {
    namespace = "app.songyan.ime.ime"
    buildFeatures { compose = true }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // First-install acceptance requires an explicitly selected isolated target.
        val bootstrapTarget =
            providers.gradleProperty("android.testInstrumentationRunnerArguments.songyan.bootstrap.target")
        val excludedTests = mutableListOf<String>()
        if (!bootstrapTarget.isPresent) {
            excludedTests += "app.songyan.ime.ime.BuiltinScriptDefaultDeviceTest"
        }
        // Offline catalogue tests need archives staged in this runner's own cache.
        if (!providers.gradleProperty("android.testInstrumentationRunnerArguments.traditionalArchivesDir").isPresent) {
            excludedTests += "app.songyan.ime.ime.ZhuyinKeyboardDeviceTest"
            excludedTests += "app.songyan.ime.ime.CangjieKeyboardDeviceTest"
        }
        if (!providers.gradleProperty("android.testInstrumentationRunnerArguments.kagiroiFixture").isPresent) {
            excludedTests += "app.songyan.ime.ime.JapaneseRomajiDeviceTest"
        }
        if (excludedTests.isNotEmpty()) testInstrumentationRunnerArguments["notClass"] = excludedTests.joinToString(",")
    }
}

dependencies {
    api(project(":core:keyboard"))
    api(project(":core:layout"))
    api(project(":core:candidate"))
    api(project(":core:appearance"))
    api(project(":core:content"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.savedstate)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Editor behaviour is tested against the real engine, never a stub that does the engine's work.
    androidTestImplementation(project(":core:rime"))
    androidTestImplementation(project(":core:packages"))
}
