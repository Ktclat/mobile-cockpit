plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:runtime-api"))
    implementation(project(":agent:skill-api"))
    implementation(project(":security:permission-api"))
    implementation(project(":security:egress-api"))
    implementation(project(":security:vault-api"))
    implementation(project(":integration:provider-api"))
    implementation(project(":integration:execution-api"))
    implementation(project(":data:persistence-api"))
    implementation(project(":platform:background-api"))
}
