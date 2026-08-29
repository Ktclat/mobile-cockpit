plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    inputs.files(
        rootProject.file("gradle/libs.versions.toml"),
        rootProject.file("gradle/verification-metadata.xml"),
        rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
        rootProject.file("gradle/wrapper/gradle-wrapper.jar"),
        rootProject.file("gradle.properties"),
    )
}
