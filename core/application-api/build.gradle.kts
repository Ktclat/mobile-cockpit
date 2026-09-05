plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":integration:provider-api"))
    implementation(project(":data:projection-models"))
    implementation(project(":security:byte-renderer-api"))
    implementation(libs.coroutines.core)
}
