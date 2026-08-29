plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":security:vault-api"))
}
