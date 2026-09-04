plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fetchclone.feature.receipts"
}

dependencies {
    // Note what is NOT here: no Room, no Retrofit, no WorkManager. This module sees
    // exactly one thing from the data layer -- the `ReceiptRepository` interface and
    // the `Receipt` / `ReceiptStatus` domain types. That is what keeps the ViewModel
    // test a plain JUnit test with a hand-written fake.
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // LazyColumn and the curated icon set are used directly here rather than leaning
    // on material3's transitive deps -- same rationale as :feature:offers.
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
