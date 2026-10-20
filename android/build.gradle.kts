plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    id("valence.module-graph")
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint().setEditorConfigPath(file("../.editorconfig"))
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint().setEditorConfigPath(file("../.editorconfig"))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../config/detekt/detekt.yml"))
    source.setFrom(
        fileTree(projectDir) {
            include("**/src/**/*.kt", "**/src/**/*.kts")
            exclude("**/build/**", "**/.gradle/**")
        },
    )
}
