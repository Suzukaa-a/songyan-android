plugins {
    id("app.songyan.android.library")
}

android {
    namespace = "app.songyan.ime.script"
    ndkVersion = "30.0.16248370"
    buildFeatures {
        buildConfig = false
        aidl = true
    }
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
        minSdk = 30
        ndk { abiFilters += abis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                targets += "songyan-script"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    // The tool contract (state machine, limits) is pure JVM; the engine never leaks into it.
    api(project(":core:tools"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Only the test classpath uses the keyboard's editor gateway; :ime never depends on :script.
    androidTestImplementation(project(":ime"))
}
