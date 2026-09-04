package com.fetchclone.core.network.di

import com.fetchclone.core.network.FetchCloneApi
import com.fetchclone.core.network.auth.AuthInterceptor
import com.fetchclone.core.network.auth.TokenAuthenticator
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

/**
 * The app's HTTP stack: one JSON codec, one connection pool, and **two clients built from
 * it**.
 *
 * Moved here from `:core:data` when authentication arrived, which is the point of
 * `:core:network` existing. A module boundary earns its keep when a concern has several
 * consumers and there is a rule worth enforcing with the compiler. The rule is *nothing
 * above this module may see an OkHttp type or a token*, and because `okhttp3` is an
 * `implementation` dependency here, that is now a compile error rather than a code-review
 * convention.
 *
 * ## The dependency cycle this file exists to break
 *
 * The naive wiring does not compile, and the error is worth being able to predict:
 *
 * ```
 * OkHttpClient -> TokenAuthenticator -> TokenRefresher -> AuthApi -> Retrofit -> OkHttpClient
 * ```
 *
 * Hilt reports it as a dependency cycle. There are two ways out.
 *
 * **Declined: `dagger.Lazy<AuthApi>` or `Provider<AuthApi>`.** Defers the resolution, so it
 * compiles. It also leaves the refresh call travelling over the *authenticated* client,
 * which means a 401 from the refresh endpoint re-enters `TokenAuthenticator` and triggers
 * another refresh. `priorResponse` bounds the recursion, so it terminates — after doing
 * the wrong thing twice, with logs that make no sense.
 *
 * **Chosen: two clients, one connection pool.** `AuthApi` is built on [BaseClient], which
 * has no interceptor and no authenticator. Refresh recursion becomes *structurally
 * impossible* rather than merely bounded — the machinery that could recurse is not
 * installed on the client the refresh travels over.
 *
 * ## `newBuilder()` is the load-bearing call
 *
 * The authenticated client is derived with [OkHttpClient.newBuilder], not constructed with
 * a second `OkHttpClient.Builder()`. The derived client **shares** the connection pool,
 * dispatcher, thread pool and DNS cache with its parent. Two independent builders would
 * mean two socket pools to the same host, so a login and the offers feed that follows it
 * could not reuse a connection.
 *
 * This is exactly the case the old `:core:data` `NetworkModule` anticipated in a comment:
 * *"Splitting them would be justified if receipt submission needed its own auth
 * interceptor… the point at which to use `newBuilder()` so the derived client still
 * shares the pool rather than constructing a fresh one."* It arrived.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    /**
     * The shared kotlinx.serialization codec.
     *
     * Each setting earns its place:
     * - `ignoreUnknownKeys` — DummyJSON returns far more per product, and per user, than
     *   the DTOs model; a strict parser would throw on every response. It also means a
     *   backend adding a field cannot break a shipped client.
     * - `coerceInputValues` — a `null` for a non-null Kotlin property becomes the declared
     *   default instead of an exception.
     * - `explicitNulls = false` — nulls are omitted when encoding rather than written as
     *   `"field": null`.
     *
     * Note what is *not* set: `encodeDefaults`. It stays `false`, and the reason is
     * documented at length on `CartRequest` — flipping it globally to fix one DTO would
     * change the payload of every other model in the app. `LoginRequest.expiresInMins`
     * carries no default for the same reason.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Request logging at `BASIC`: method, URL, status, timing. No headers, no bodies.
     *
     * [HttpLoggingInterceptor.redactHeader] is the load-bearing call now that requests
     * carry a bearer token. `BASIC` does not print headers, so today the redaction changes
     * nothing — but the level is one keystroke from `HEADERS` during a debugging session,
     * and that is exactly the moment a token would otherwise be written to logcat, where
     * bug-report capture collects it. Setting it here means the safe behaviour survives
     * that edit instead of depending on whoever makes it remembering.
     *
     * Installed on the **base** client, so it is inherited by the authenticated one
     * through `newBuilder()` and logs both.
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }

    @Provides
    @ApiHost
    fun provideApiHost(): String = FetchCloneApi.HOST

    @Provides
    @Singleton
    @BaseClient
    fun provideBaseClient(logging: HttpLoggingInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

    /**
     * The client every ordinary API call uses.
     *
     * Unqualified on purpose: a service added later gets authentication by default, and
     * opting out is the deliberate act. See [BaseClient] for why that default direction is
     * the safer one.
     *
     * The ordering of the two pieces is not arbitrary, and it is the shape of the whole
     * design in four lines:
     *
     * - [AuthInterceptor] runs **before** the request is sent. It attaches the token, and
     *   refreshes first if that token is nearly stale.
     * - [TokenAuthenticator] runs **after** a 401 comes back. It refreshes and OkHttp
     *   replays the request.
     *
     * Attach and refresh are different mechanisms at different points in the lifecycle.
     * Using an interceptor for both is the classic mistake, and this wiring is where the
     * distinction is expressed.
     */
    @Provides
    @Singleton
    fun provideAuthenticatedClient(
        @BaseClient base: OkHttpClient,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    @AuthRetrofit
    fun provideAuthRetrofit(@BaseClient client: OkHttpClient, json: Json): Retrofit =
        retrofit(client, json)

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = retrofit(client, json)

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(FetchCloneApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory(APPLICATION_JSON))
        .build()

    private val APPLICATION_JSON = "application/json".toMediaType()
}
