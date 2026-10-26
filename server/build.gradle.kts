plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    id("com.diffplug.spotless") version "8.10.4"
    id("dev.detekt") version "2.0.0-alpha.6"
}

group = "com.lnight.valence"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("com.lnight.valence.server.ApplicationKt")
}

repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "SignalBuildArtifacts"
                url = uri("https://build-artifacts.signal.org/libraries/maven/")
            }
        }
        filter {
            includeGroup("org.signal")
        }
    }
    mavenCentral()
}

dependencies {
    implementation("com.lnight.valence:protocol")

    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    implementation("org.signal:libsignal-server:0.106.0")

    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    testImplementation(testFixtures("com.lnight.valence:protocol"))
    testImplementation(kotlin("test"))
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

val allowedDependencyGroups =
    setOf(
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
        "io.ktor",
        "ch.qos.logback",
        "com.lnight.valence",
        "org.signal",
    )

val productionDependencies =
    provider {
        listOf("api", "implementation", "compileOnly", "runtimeOnly")
            .mapNotNull { configurations.findByName(it) }
            .flatMap { it.dependencies }
            .map { "${it.group}:${it.name}" }
    }

tasks.register("checkDependencies") {
    group = "verification"
    description = "Verifies that the server depends only on allowed library groups"
    inputs.property("dependencies", productionDependencies)
    doLast {
        val violations = productionDependencies.get().filterNot { it.substringBefore(':') in allowedDependencyGroups }
        check(violations.isEmpty()) { "server must not depend on: $violations. Extend the allowlist deliberately." }
    }
}

tasks.check {
    dependsOn("spotlessCheck", "detekt", "checkDependencies")
}
