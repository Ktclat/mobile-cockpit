plugins {
    `java-library`
}

dependencies {
    implementation(project(":security:egress-api"))
    implementation(project(":core:domain"))
    implementation(project(":data:persistence-api"))
}
