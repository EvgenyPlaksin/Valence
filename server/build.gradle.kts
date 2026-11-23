plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    id("com.diffplug.spotless") version "8.11.0"
    id("dev.detekt") version "2.0.0-alpha.6"
}

group = "com.lnight.valence"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("com.lnight.valence.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=75.0")
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

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation("com.lnight.valence:protocol")

    implementation("io.ktor:ktor-server-core:3.6.1")
    implementation("io.ktor:ktor-server-netty:3.6.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-server-auth:3.6.1")
    implementation("io.ktor:ktor-server-websockets:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.13.0")
    implementation("org.signal:libsignal-server:0.106.2")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")

    implementation("ch.qos.logback:logback-classic:1.6.8")

    testImplementation("io.ktor:ktor-server-test-host:3.6.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    testImplementation("io.ktor:ktor-client-websockets:3.6.1")
    testImplementation(testFixtures("com.lnight.valence:protocol"))
    testImplementation(kotlin("test"))
    testImplementation("com.lemonappdev:konsist:0.18.1")

    add("integrationTestImplementation", "org.testcontainers:postgresql:1.21.3")
    add("integrationTestImplementation", "org.testcontainers:junit-jupiter:1.21.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against PostgreSQL using Testcontainers"
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("api.version", "1.44")
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
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
        "org.postgresql",
        "com.zaxxer",
        "org.flywaydb",
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

fun registerTool(
    taskName: String,
    toolClass: String,
    text: String,
    argumentProperties: List<String>,
) {
    tasks.register<JavaExec>(taskName) {
        group = "tools"
        description = text
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(toolClass)
        doFirst {
            args =
                argumentProperties.map { property ->
                    providers.gradleProperty(property).orNull
                        ?: throw GradleException("Pass -P$property=<value>")
                }
        }
    }
}

registerTool(
    "generateTrustRoot",
    "com.lnight.valence.server.crypto.GenerateTrustRootKt",
    "Generates a trust root key pair. Pass -PkeyOutputDirectory=<absolute directory outside the repository>",
    listOf("keyOutputDirectory"),
)
registerTool(
    "generateServerKey",
    "com.lnight.valence.server.crypto.GenerateServerKeyKt",
    "Generates a server signing key pair. Pass -PkeyOutputDirectory=<absolute directory outside the repository>",
    listOf("keyOutputDirectory"),
)
registerTool(
    "signServerCertificate",
    "com.lnight.valence.server.crypto.SignServerCertificateKt",
    "Signs a server certificate with VALENCE_TRUST_ROOT_PRIVATE_KEY. Pass -PserverPublicKey=<key> -PkeyId=<id>",
    listOf("serverPublicKey", "keyId"),
)
registerTool(
    "generateCertificateKeys",
    "com.lnight.valence.server.crypto.CertificateKeyToolKt",
    "DEVELOPMENT ONLY: generates a certificate chain. Pass -PkeyOutputDirectory=<absolute directory outside the repository>",
    listOf("keyOutputDirectory"),
)
