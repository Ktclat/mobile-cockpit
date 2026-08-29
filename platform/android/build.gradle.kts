plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:runtime"))
    implementation(project(":agent:skill-runtime"))
    implementation(project(":security:permission"))
    implementation(project(":security:egress"))
    implementation(project(":security:vault"))
    implementation(project(":integration:provider"))
    implementation(project(":integration:ssh"))
    implementation(project(":data:persistence-room"))
    implementation(project(":data:projection"))
    implementation(project(":platform:background"))
}
