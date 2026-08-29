plugins {
    `java-library`
}

dependencies {
    implementation(project(":data:persistence-api"))
    implementation(project(":core:domain"))
}
