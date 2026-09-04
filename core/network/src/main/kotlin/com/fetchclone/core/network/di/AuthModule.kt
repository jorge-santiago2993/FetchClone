package com.fetchclone.core.network.di

import com.fetchclone.core.network.auth.AuthApi
import com.fetchclone.core.network.auth.AuthClient
import com.fetchclone.core.network.auth.DefaultAuthClient
import com.fetchclone.core.network.auth.ProfileApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Wires the session endpoints, and is the one file where the two-client split becomes
 * visible as two different `Retrofit` parameters.
 *
 * Reading the two providers side by side is the fastest way to understand the design:
 * [AuthApi] takes `@AuthRetrofit` and [ProfileApi] takes the unqualified one. Login and
 * refresh travel over a client that has never heard of authentication; everything else
 * travels over the client that carries a token and retries a 401.
 *
 * Kept separate from [NetworkModule] because that module answers "how does this app make
 * an HTTP call" and this one answers "which calls are about the session". The receipt
 * pipeline earned its own `ReceiptModule` on the same reasoning — a decision worth finding
 * should not be one line in a file of generic bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface AuthModule {

    @Binds
    @Singleton
    fun bindAuthClient(impl: DefaultAuthClient): AuthClient

    companion object {

        /**
         * Login and refresh, on the **unauthenticated** client.
         *
         * If this took the unqualified `Retrofit`, a 401 from `auth/refresh` would invoke
         * `TokenAuthenticator`, which calls this API, which 401s again. See
         * [NetworkModule]'s doc for why breaking the cycle structurally beats bounding it.
         */
        @Provides
        @Singleton
        fun provideAuthApi(@AuthRetrofit retrofit: Retrofit): AuthApi =
            retrofit.create(AuthApi::class.java)

        /**
         * `auth/me`, on the **authenticated** client — it is an ordinary API call that
         * happens to be about the user, and it wants the full token pipeline.
         */
        @Provides
        @Singleton
        fun provideProfileApi(retrofit: Retrofit): ProfileApi =
            retrofit.create(ProfileApi::class.java)
    }
}
