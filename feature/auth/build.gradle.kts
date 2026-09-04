plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fetchclone.feature.auth"
}

dependencies {
    // Note what is NOT here, and that it is the whole point of this feature:
    // no :core:network, no OkHttp, no Retrofit, no TokenStore. This module cannot
    // reference a token even by accident -- `:core:data` depends on `:core:network`
    // with `implementation`, so none of it reaches this compile classpath.
    //
    // The login screen sees exactly two things: `AuthRepository` and `AuthState`.
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    // MainDispatcherRule, shared across the three feature modules.
    testImplementation(project(":core:testing"))
}
