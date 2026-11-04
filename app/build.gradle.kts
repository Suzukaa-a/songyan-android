import java.util.Properties

plugins {
    id("app.songyan.android.application")
    id("app.songyan.android.compose")
    alias(libs.plugins.baselineprofile)
}

// Release signing comes from SONGYAN_KEYSTORE_FILE / SONGYAN_KEYSTORE_PASSWORD / SONGYAN_KEY_ALIAS /
// SONGYAN_KEY_PASSWORD (the release workflow) or from the git-ignored .local/release-signing.properties
// (keys: storeFile, storePassword, keyAlias, keyPassword), so no secret is ever committed. The
// environment wins. Without either the release build is produced unsigned (CI and fresh checkouts
// still build and shrink).
val signingFile =
    Properties().apply {
        val file = rootDir.resolve(".local/release-signing.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

fun signingValue(
    envName: String,
    propertyName: String,
): String? = System.getenv(envName)?.takeIf { it.isNotEmpty() } ?: signingFile.getProperty(propertyName)

val releaseStoreFile = signingValue("SONGYAN_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("SONGYAN_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("SONGYAN_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("SONGYAN_KEY_PASSWORD", "keyPassword")
val hasReleaseKey = releaseStoreFile != null

val rimeDataHash =
    providers.of(ContentHashValueSource::class.java) {
        parameters.roots.set(
            listOf(
                rootDir.resolve("core/rime/src/main/assets/rime").path,
                projectDir.resolve("src/main/assets/rime").path,
            ),
        )
    }
val engineSourceHash =
    providers.of(ContentHashValueSource::class.java) {
        parameters.roots.set(listOf(rootDir.resolve("native").path))
    }

android {
    namespace = "app.songyan.ime"
    // AGP strips packaged native libraries with the NDK of the module that packages them;
    // without this it finds no strip tool and ships the unstripped engine.
    ndkVersion = "30.0.16248370"
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // A relative path is taken from the repository root; an absolute one stays as is.
                storeFile = rootDir.resolve(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        // Debug and the benchmark builds get their own application IDs so test runs never replace or
        // uninstall a release install that someone types with every day.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unstripped engine symbols are packaged next to the bundle for crash symbolication.
            ndk { debugSymbolLevel = "FULL" }
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Dependency translations must not claim languages the app does not translate.
        localeFilters += listOf("en", "zh")
    }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The deploy benchmark takes minutes and needs a scheme package pushed to the device, so
        // the connected test tasks leave it out; it runs through `am instrument -e class`. The
        // Gradle test report counts a JUnit assumption as a failure, so skipping it there does not work.
        testInstrumentationRunnerArguments["notClass"] =
            "app.songyan.ime.deploy.DeploySpeedBenchmark,app.songyan.ime.deploy.KagiroiDeploymentDeviceTest"
        // Parts of the generation build stamp (BuildStamps): a published generation is rebuilt when
        // the bundled Rime data or the engine sources (librime pin, patches, plugins) change, even
        // when the version code does not.
        buildConfigField("String", "RIME_DATA_HASH", "\"${rimeDataHash.get()}\"")
        buildConfigField("String", "ENGINE_SOURCE_HASH", "\"${engineSourceHash.get()}\"")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // A GitHub release offers one APK per ABI plus a universal one, since an app bundle only means
    // something to Play; everyday builds keep a single APK.
    if (providers.gradleProperty("songyan.abiSplits").orNull == "true") {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64")
                isUniversalApk = true
            }
        }
    }
    bundle {
        abi { enableSplit = true }
        language {
            // The IME applies the app locale to its service context at runtime, so all
            // languages must be present when the app is installed (no Play language splits).
            enableSplit = false
        }
    }
}

// The Baseline Profile plugin adds benchmarkRelease and nonMinifiedRelease: release code, R8 and
// the optimized engine, not debuggable. They only run on test devices, so without a release key
// they are signed with the debug key to be installable; release itself is never re-signed.
androidComponents {
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.applicationId.set("app.songyan.ime.benchmark")
        if (!hasReleaseKey) {
            variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
        }
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.applicationId.set("app.songyan.ime.benchmark")
        if (!hasReleaseKey) {
            variant.signingConfig.setConfig(android.signingConfigs.getByName("debug"))
        }
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(project(":ime"))
    implementation(project(":settings"))
    implementation(project(":core:rime"))
    implementation(project(":core:keyboard"))
    implementation(project(":core:layout"))
    implementation(project(":core:candidate"))
    implementation(project(":core:appearance"))
    implementation(project(":core:content"))
    implementation(project(":core:packages"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
