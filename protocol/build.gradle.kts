plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("java-test-fixtures")
    id("com.diffplug.spotless") version "8.10.4"
    id("dev.detekt") version "2.0.0-alpha.6"
}

group = "com.lnight.valence"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project))
    testImplementation("com.lemonappdev:konsist:0.18.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().setEditorConfigPath(file("../.editorconfig"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint().setEditorConfigPath(file("../.editorconfig"))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../config/detekt/detekt.yml"))
    source.setFrom(files("src"))
}

val allowedDependencyGroups = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")

val productionDependencies =
    provider {
        listOf("api", "implementation", "compileOnly", "runtimeOnly")
            .flatMap { configurations.getByName(it).dependencies }
            .map { "${it.group}:${it.name}" }
    }

tasks.register("checkDependencies") {
    group = "verification"
    description = "Verifies that protocol depends only on Kotlin and kotlinx libraries"
    inputs.property("dependencies", productionDependencies)
    doLast {
        val violations = productionDependencies.get().filterNot { it.substringBefore(':') in allowedDependencyGroups }
        check(violations.isEmpty()) { "protocol must not depend on: $violations" }
    }
}

tasks.check {
    dependsOn("spotlessCheck", "detekt", "checkDependencies")
}
