plugins {
    `kotlin-dsl`
}

group = "app.songyan.ime.buildlogic"

dependencies {
    compileOnly("com.android.tools.build:gradle:9.4.1")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:14.2.0")
}

gradlePlugin {
    plugins {
        create("androidLibrary") {
            id = "app.songyan.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        create("androidApplication") {
            id = "app.songyan.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        create("jvmLibrary") {
            id = "app.songyan.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        create("androidTest") {
            id = "app.songyan.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        create("androidCompose") {
            id = "app.songyan.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}