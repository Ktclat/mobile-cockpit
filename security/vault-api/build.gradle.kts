plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":integration:execution-api"))
    implementation(project(":integration:provider-api"))
}
