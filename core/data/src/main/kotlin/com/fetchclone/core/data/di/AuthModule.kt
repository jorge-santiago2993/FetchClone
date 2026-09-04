package com.fetchclone.core.data.di

import com.fetchclone.core.data.repository.AuthRepository
import com.fetchclone.core.data.repository.DefaultAuthRepository
import com.fetchclone.core.data.repository.SessionStateHolder
import com.fetchclone.core.network.auth.SessionInvalidator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the session.
 *
 * ## The second binding is the interesting one
 *
 * [DefaultAuthRepository] is bound **twice**, to two different interfaces, and it is the
 * same `@Singleton` instance both times — that is what `@Binds` of an already-scoped
 * implementation gives you. It matters: if Hilt created two instances, the one
 * `TokenRefresher` invalidates would not be the one the UI observes, and a forced logout
 * would update a `StateFlow` nobody is collecting. Nothing would appear broken until a
 * session expired in the wild.
 *
 * The direction of that second binding is also worth noticing, because it inverts the
 * usual reading of the module graph. `:core:data` depends on `:core:network`, so
 * dependencies point downward — yet here `:core:network`'s `TokenRefresher` calls *up*
 * into `:core:data`. That is not a violation, it is what an interface is for: the callback
 * is declared in the lower module, so the lower module still depends on nothing above it,
 * and Hilt supplies the implementation at the composition root.
 *
 * This is the seam that lets `AuthState`, `AuthUser` and `SignOutReason` live here rather
 * than in the networking module. `TokenRefresher` knows only "the credential was refused";
 * what that *means* is decided one layer up.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface AuthModule {

    @Binds
    @Singleton
    fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    fun bindSessionInvalidator(impl: SessionStateHolder): SessionInvalidator
}
