plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fetchclone.feature.offers"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // Compose foundation (LazyVerticalGrid) and the curated icon set are used directly
    // here, so declare them explicitly rather than leaning on material3's transitive deps.
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // paging-runtime: PagingData / cachedIn for the ViewModel.
    // paging-compose: collectAsLazyPagingItems() for the Screen (added next pass).
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.androidx.paging.testing)
}
