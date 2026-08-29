plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:application-api"))
    implementation(project(":data:projection-models"))
    implementation(project(":security:byte-renderer-api"))
}
