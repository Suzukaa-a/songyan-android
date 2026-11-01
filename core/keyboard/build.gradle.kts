plugins {
    id("app.songyan.jvm.library")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
}
