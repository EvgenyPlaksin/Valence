plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "com.lnight.valence"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("com.lnight.valence.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.lnight.valence:protocol")

    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    testImplementation(testFixtures("com.lnight.valence:protocol"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
