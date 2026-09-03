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

    // WorkManager lives here for the same reason Paging does: `ReceiptUploadWorker` is
    // the durable trigger for a data-layer process (the outbox drain), and keeping the
    // trigger next to the processor it drives means one module owns the whole
    // submission pipeline. :feature:receipts never sees WorkManager at all.
    //
    // Alternative considered and declined: a dedicated `:sync:work` module, which is
    // what Now in Android does. That earns its keep when several features need
    // background sync and you want one scheduling policy across them. With exactly one
    // worker it would be a module to wire for no gain -- promote it the day a second
    // feature needs background work.
    implementation(libs.androidx.work.runtime)
    // @HiltWorker needs BOTH annotation processors: dagger's hilt-android-compiler
    // (already below) builds the Hilt graph, while androidx.hilt's hilt-compiler
    // generates the AssistedFactory that HiltWorkerFactory looks up at runtime.
    // Omitting this one compiles fine and then fails at runtime with
    // "Could not instantiate ReceiptUploadWorker" -- a genuinely confusing failure.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.retrofit.core)
    testImplementation(libs.retrofit.converter.kotlinx.serialization)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.work.testing)
}
