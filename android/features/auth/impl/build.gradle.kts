plugins {
    id("valence.android.compose")
    id("valence.android.hilt")
}

android {
    namespace = "com.lnight.valence.features.auth.impl"
}

dependencies {
    implementation(project(":features:auth:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
