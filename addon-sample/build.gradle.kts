plugins {
    id("app.songyan.android.application")
}

// The sample addon is the third-party template and the integration fixture at the same time. It is
// a standalone APK: Songyan binds to its service, never loads its code, and never installs it.
//
// A real addon pins Songyan's published signing certificate here (or via the Gradle property
// songyan.addonHostSha256) so its exported service only accepts the keyboard. The debug build
// additionally accepts unverified callers, because that is what the repository's integration tests
// and the device probes need; a release build never does.
val hostSha256: String = (findProperty("songyan.addonHostSha256") as String?)?.trim().orEmpty()

android {
    namespace = "app.songyan.ime.addon.sample"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "app.songyan.ime.addon.sample"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SONGYAN_HOST_SHA256", "\"" + hostSha256 + "\"")
    }
}

dependencies {
    // Third-party addons only need this one artifact. It is Apache-2.0 on purpose (decision D54).
    implementation(project(":addon-api"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
