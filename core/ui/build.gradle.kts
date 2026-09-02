plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fetchclone.core.ui"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
}
