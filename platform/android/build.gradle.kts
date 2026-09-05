plugins {
    alias(libs.plugins.android.library)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

android {
    namespace = "dev.cockpit.platform.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
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
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
