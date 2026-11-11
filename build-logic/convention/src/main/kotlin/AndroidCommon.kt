import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.JavaVersion

/**
 * Shared Android configuration for every module: API levels, Java/Kotlin target,
 * lint and packaging defaults. Kept in one place so modules cannot drift.
 *
 * The application/library extensions are configured separately because AGP 9's
 * new DSL exposes our settings through typed block functions; the untyped
 * CommonExtension getters are hidden from Kotlin.
 */
internal fun ApplicationExtension.configureCommon() {
    compileSdk { version = release(37) }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 30
        targetSdk = 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    packaging { resources { excludes.add("META-INF/{AL2.0,LGPL2.1}") } }
}

internal fun LibraryExtension.configureCommon() {
    compileSdk { version = release(37) }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    packaging { resources { excludes.add("META-INF/{AL2.0,LGPL2.1}") } }
}

internal fun TestExtension.configureCommon() {
    compileSdk { version = release(37) }
    buildToolsVersion = "37.0.0"
    // Matches the app: benchmarks run on the devices it ships to.
    defaultConfig {
        minSdk = 30
        targetSdk = 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
