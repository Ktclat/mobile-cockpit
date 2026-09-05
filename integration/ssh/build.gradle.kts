plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":integration:execution-api"))
    implementation(project(":security:vault-api"))
    implementation(project(":data:persistence-api"))
}
