plugins {
    id("valence.android.library")
    id("valence.android.hilt")
}

android {
    namespace = "com.lnight.valence.core.network"
}

dependencies {
    implementation(project(":core:model"))
    implementation("com.lnight.valence:protocol")

    testImplementation(libs.junit)
}
