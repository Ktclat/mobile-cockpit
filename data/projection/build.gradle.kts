plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":data:persistence-api"))
    implementation(project(":data:projection-models"))
}
