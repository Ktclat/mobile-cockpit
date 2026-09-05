plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:application-api"))
    api(project(":core:domain"))
    implementation(project(":core:runtime-api"))
    implementation(project(":security:byte-renderer"))
    implementation(project(":security:vault-api"))
    api(project(":data:persistence-api"))
    api(project(":data:projection-models"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
