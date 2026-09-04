package com.fetchclone.core.data.di

import com.fetchclone.core.data.network.CartsApi
import com.fetchclone.core.data.network.ProductsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Creates this module's Retrofit services from the [Retrofit] that `:core:network` builds.
 *
 * ## Why the split lands here
 *
 * `:core:network` owns *how* the app talks to the backend — the connection pool, the JSON
 * codec, the bearer token, the refresh dance. This module owns *what* it asks for.
 *
 * The practical consequence is the one worth noticing: neither [ProductsApi] nor
 * [CartsApi] knows that authentication exists. They declare paths and parameters; the
 * `Authorization` header is attached beneath them by an interceptor they have never heard
 * of, and a 401 is retried beneath them by an authenticator they have never heard of. When
 * the paths moved to their `auth/...` variants, the *only* change to these files was the
 * string in the annotation.
 *
 * That is the same boundary the feature modules get, one layer down, and it is the reason
 * adding auth to this app was mostly new files rather than edits to old ones.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideProductsApi(retrofit: Retrofit): ProductsApi =
        retrofit.create(ProductsApi::class.java)

    @Provides
    @Singleton
    fun provideCartsApi(retrofit: Retrofit): CartsApi =
        retrofit.create(CartsApi::class.java)
}
