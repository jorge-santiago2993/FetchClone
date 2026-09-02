plugins {
    alias(libs.plugins.fetchclone.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fetchclone.core.data"
}

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    // room-paging swaps Room's generated PagingSource for one built on Paging 3's
    // LimitOffsetPagingSource, which is what lets a @Query return PagingSource<Int, T>
    // and drives the "Room is the single source of truth" data flow.
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Paging 3 lives in :core:data because the Pager + RemoteMediator (network -> DB)
    // are a data-layer concern; the feature module only consumes Flow<PagingData<Offer>>.
    implementation(libs.androidx.paging.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.retrofit.core)
    testImplementation(libs.retrofit.converter.kotlinx.serialization)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.paging.testing)
}
