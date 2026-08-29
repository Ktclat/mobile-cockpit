pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Cockpit"
include(":architecture-tests")
include(":app")
include(":presentation")
include(":core:domain")
include(":core:application-api")
include(":core:application")
include(":core:runtime-api")
include(":core:runtime")
include(":agent:skill-api")
include(":agent:skill-runtime")
include(":integration:execution-api")
include(":integration:ssh")
include(":integration:provider-api")
include(":integration:provider")
include(":data:persistence-api")
include(":data:persistence-room")
include(":data:projection-models")
include(":data:projection")
include(":security:byte-renderer-api")
include(":security:byte-renderer")
include(":security:vault-api")
include(":security:vault")
include(":security:permission-api")
include(":security:permission")
include(":security:egress-api")
include(":security:egress")
include(":platform:background-api")
include(":platform:background")
include(":platform:android")
include(":test-support")
include(":security-tests")
include(":spikes:ssh-transport")
