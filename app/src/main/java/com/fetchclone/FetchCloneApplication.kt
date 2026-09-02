package com.fetchclone

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp

/**
 * Provides the app-wide Coil [ImageLoader].
 *
 * Coil 3 splits network support into a separate artifact and would otherwise build a
 * default loader lazily on the first request. Declaring it here makes the setup
 * explicit: an OkHttp-backed network fetcher plus a crossfade so images fade in instead
 * of popping. Coil keeps its own connection pool (separate from Retrofit's) — fine, and
 * the usual recommendation for image traffic.
 */
@HiltAndroidApp
class FetchCloneApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
