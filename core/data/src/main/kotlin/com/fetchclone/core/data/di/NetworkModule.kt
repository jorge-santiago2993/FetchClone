package com.fetchclone.core.data.di

import com.fetchclone.core.data.network.CartsApi
import com.fetchclone.core.data.network.ProductsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(ProductsApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
        .build()

    @Provides
    @Singleton
    fun provideProductsApi(retrofit: Retrofit): ProductsApi = retrofit.create(ProductsApi::class.java)

    /**
     * Both APIs share one [Retrofit] and therefore one [OkHttpClient].
     *
     * That sharing is the point, not an accident of convenience: OkHttp's connection pool,
     * thread pool and DNS cache live on the client, so a second instance would open a
     * second set of sockets to the same host and lose connection reuse between a feed page
     * load and a receipt upload. The documented guidance is one `OkHttpClient` per
     * application unless you specifically need different interceptors or timeouts.
     *
     * Splitting them would be justified if receipt submission needed its own auth
     * interceptor or a longer write timeout — a plausible future, and the point at which
     * to use `newBuilder()` so the derived client still *shares* the pool rather than
     * constructing a fresh one.
     */
    @Provides
    @Singleton
    fun provideCartsApi(retrofit: Retrofit): CartsApi = retrofit.create(CartsApi::class.java)

    private val APPLICATION_JSON = "application/json".toMediaType()
}
