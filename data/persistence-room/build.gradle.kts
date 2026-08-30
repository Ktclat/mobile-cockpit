plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":data:persistence-api"))
    implementation(project(":core:domain"))
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.room.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

tasks.test {
    useJUnitPlatform()
}
