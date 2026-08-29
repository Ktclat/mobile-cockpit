plugins {
    `java-library`
}

dependencies {
    implementation(project(":platform:background-api"))
    implementation(project(":core:runtime-api"))
    implementation(project(":data:projection-models"))
}
