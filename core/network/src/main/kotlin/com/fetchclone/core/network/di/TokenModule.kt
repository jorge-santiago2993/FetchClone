package com.fetchclone.core.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.fetchclone.core.network.token.DataStoreSessionStore
import com.fetchclone.core.network.token.ElapsedTime
import com.fetchclone.core.network.token.KeystoreTokenCipher
import com.fetchclone.core.network.token.TokenCipher
import com.fetchclone.core.network.token.SessionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires credential storage.
 *
 * Split from [NetworkModule] because these are different concerns that happen to live in
 * the same Gradle module: one builds an HTTP stack, the other decides where a secret is
 * kept. Keeping "which cipher protects the refresh token" findable in a file of its own is
 * the same instinct that gave the receipt pipeline its own `ReceiptModule` rather than one
 * more line in a generic bindings file.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface TokenModule {

    @Binds
    @Singleton
    fun bindSessionStore(impl: DataStoreSessionStore): SessionStore

    @Binds
    @Singleton
    fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    companion object {

        /**
         * The session's DataStore.
         *
         * Constructed through [PreferenceDataStoreFactory] rather than the more familiar
         * `by preferencesDataStore(name)` property delegate. The delegate is a
         * `Context` extension holding process-global state, which reads oddly in a module
         * whose whole point is that dependencies are declared: with the factory, the
         * single-instance guarantee comes from `@Singleton` and the graph, and a test can
         * supply a DataStore rooted in a temporary directory without touching a global.
         *
         * DataStore permits exactly one active instance per file per process and throws if
         * that is violated, so "who owns the instance" is a question worth answering
         * explicitly rather than by convention.
         */
        @Provides
        @Singleton
        fun provideSessionDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile(SESSION_PREFERENCES)
        }

        @Provides
        @Singleton
        fun provideElapsedTime(): ElapsedTime = ElapsedTime.System

        /**
         * Resolves to `files/datastore/session.preferences_pb`.
         *
         * That path is repeated in `AndroidManifest`'s backup rules, which exclude it from
         * cloud backup and device transfer. The two must stay in step: the Keystore key
         * that decrypts this file is device-bound and is never backed up, so a restored
         * copy is undecryptable by construction. Backing it up would achieve nothing except
         * putting an encrypted credential somewhere it does not need to be.
         */
        private const val SESSION_PREFERENCES = "session"
    }
}
