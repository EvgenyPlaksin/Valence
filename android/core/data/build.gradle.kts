plugins {
    id("valence.android.library")
}

android {
    namespace = "com.lnight.valence.core.data"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
}
