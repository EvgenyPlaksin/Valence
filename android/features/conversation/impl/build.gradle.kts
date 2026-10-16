plugins {
    id("valence.android.compose")
    id("valence.android.hilt")
}

android {
    namespace = "com.lnight.valence.features.conversation.impl"
}

dependencies {
    implementation(project(":features:conversation:api"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
