plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":integration:execution-api"))
    api(project(":integration:provider-api"))
}
