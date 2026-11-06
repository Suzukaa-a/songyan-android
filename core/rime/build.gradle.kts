plugins {
    id("app.songyan.android.library")
}

// The pinned librime version has one source, native/VERSIONS.env (the release line in its
// header comment, next to the commit); native builds cross-check it against the compiled marker.
val librimeVersion: String =
    providers
        .fileContents(rootProject.layout.projectDirectory.file("native/VERSIONS.env"))
        .asText
        .get()
        .lineSequence()
        .firstNotNullOfOrNull { Regex("""^# Release (\d+\.\d+\.\d+)\b""").find(it)?.groupValues?.get(1) }
        ?: error("native/VERSIONS.env has no '# Release X.Y.Z' line for LIBRIME_COMMIT")

android {
    namespace = "app.songyan.ime.rime"
    ndkVersion = "30.0.16248370"
    buildFeatures { buildConfig = true }
    defaultConfig {
        // -Psongyan.abis=arm64-v8a limits local builds to the device under test; releases build all three.
        val abis =
            providers
                .gradleProperty("songyan.abis")
                .orNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        buildConfigField("String", "LIBRIME_VERSION", "\"$librimeVersion\"")
        ndk { abiFilters += abis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Offline catalogue tests need archives staged in this runner's own cache.
        if (!providers.gradleProperty("android.testInstrumentationRunnerArguments.traditionalArchivesDir").isPresent) {
            testInstrumentationRunnerArguments["notClass"] = "app.songyan.ime.rime.TraditionalReplayDeviceTest"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                targets += "songyan_rime"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../../native/android/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    api(project(":core:keyboard"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":core:packages"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
