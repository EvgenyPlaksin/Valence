plugins {
    id("valence.jvm.library")
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    inputs
        .files(
            fileTree(rootDir) {
                include("**/src/**/*.kt")
                exclude("**/build/**", "**/.gradle/**")
            },
        ).withPropertyName("analyzedSources")
}
