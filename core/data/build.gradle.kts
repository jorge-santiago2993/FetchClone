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

/*
 * Ships the exported Room schemas inside the androidTest APK.
 *
 * `MigrationTestHelper` builds an *old* schema version from these JSON files at runtime, so
 * they have to be assets of the test APK -- without them it fails with "Cannot find the
 * schema file", which reads like a missing dependency rather than a missing asset.
 *
 * Configured through `com.android.build.api.dsl.LibraryExtension` rather than the `android { }`
 * block above. AGP 9's generated Kotlin DSL accessor for `sourceSets` is still typed against
 * the legacy `AndroidLibrarySourceSet`, so `android { sourceSets.named("androidTest") { } }`
 * fails at configuration time with a ClassCastException that names two AGP-internal types and
 * says nothing about source sets. Reaching for the new DSL interface explicitly avoids the
 * stale accessor -- the same interface `AndroidLibraryConventionPlugin` already uses.
 */
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    sourceSets.named("androidTest") {
        assets.srcDirs(files("${projectDir}/schemas"))
    }
}

dependencies {
    // The HTTP stack -- client, Retrofit, JSON codec, and everything to do with the
    // bearer token -- lives in :core:network. This module keeps only the service
    // interfaces and DTOs: *what* we ask the backend for, not *how* we talk to it.
    //
    // `implementation`, not `api`, and that is the load-bearing choice: it means an
    // OkHttpClient, an Interceptor, a TokenStore or an AuthApi cannot reach the compile
    // classpath of :feature:offers or :feature:receipts even by accident. Retrofit and
    // kotlinx-serialization.json arrive transitively because :core:network exposes them
    // as `api` -- the service interfaces below need Retrofit's annotations.
    implementation(project(":core:network"))

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

    // Tests build their own Retrofit against MockWebServer (and, in one case, the live
    // API), so they need the converter and OkHttp directly rather than through
    // :core:network's providers.
    testImplementation(libs.retrofit.converter.kotlinx.serialization)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.work.testing)

    // MigrationTestHelper needs an instrumentation context, so the migration test is an
    // androidTest even though it never touches the UI.
    androidTestImplementation(libs.room.testing)
}
