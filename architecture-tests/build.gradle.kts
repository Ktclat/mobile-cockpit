plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":core:domain"))
    testImplementation(project(":core:runtime-api"))
    testImplementation(project(":test-support"))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    inputs.files(
        rootProject.file("gradle/libs.versions.toml"),
        rootProject.file("gradle/verification-metadata.xml"),
        rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
        rootProject.file("gradle/wrapper/gradle-wrapper.jar"),
        rootProject.file("gradle.properties"),
    )
}
