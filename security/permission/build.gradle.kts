plugins {
    `java-library`
}

dependencies {
    implementation(project(":security:permission-api"))
    implementation(project(":core:domain"))
}
