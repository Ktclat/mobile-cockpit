plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:application-api"))
    implementation(project(":core:domain"))
    implementation(project(":core:runtime-api"))
    implementation(project(":security:byte-renderer"))
    implementation(project(":security:vault-api"))
    implementation(project(":data:persistence-api"))
    implementation(project(":data:projection-models"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
