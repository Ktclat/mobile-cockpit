plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Runs the repository architecture evidence checks."
    dependsOn(":architecture-tests:test")
}
