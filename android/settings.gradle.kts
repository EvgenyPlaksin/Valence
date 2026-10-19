pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "valence-android"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

includeBuild("../protocol")

include(":app")
include(":core:model")
include(":core:common")
include(":core:crypto")
include(":core:database")
include(":core:network")
include(":core:data")
include(":core:ui-theme")
include(":features:auth:api")
include(":features:auth:impl")
include(":features:chat-list:api")
include(":features:chat-list:impl")
include(":features:conversation:api")
include(":features:conversation:impl")
include(":architecture-tests")
