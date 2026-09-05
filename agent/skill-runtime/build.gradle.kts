plugins {
    `java-library`
}

dependencies {
    implementation(project(":agent:skill-api"))
    implementation(project(":core:domain"))
    implementation(project(":security:permission-api"))
}
