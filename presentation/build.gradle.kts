plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

android {
    namespace = "dev.cockpit.presentation"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core:application-api"))
    implementation(project(":data:projection-models"))
    implementation(project(":security:byte-renderer-api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.core)

    testImplementation(project(":platform:android"))
    testImplementation(project(":core:domain"))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
