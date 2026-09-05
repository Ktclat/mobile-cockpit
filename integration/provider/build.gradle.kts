plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":integration:provider-api"))
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
