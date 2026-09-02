package com.fetchclone.core.data.di

import com.fetchclone.core.data.repository.DefaultOffersRepository
import com.fetchclone.core.data.repository.OffersRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds repository interfaces to their implementations.
 *
 * ### Why `@Binds` in an `interface` module, not `@Provides` in an `object`
 *
 * `@Binds` tells Hilt "when someone asks for [OffersRepository], hand them the
 * [DefaultOffersRepository] you already know how to construct." It generates no
 * factory body and does no reflection — it is the zero-overhead way to express
 * interface → impl. `@Provides` would work too but requires writing a method body and
 * an `object`; `@Binds` is the idiomatic choice whenever the impl has an
 * `@Inject constructor`.
 *
 * The `@Singleton` here must match the scope on [DefaultOffersRepository]; it is
 * repeated on the binding so the scope is visible at the graph's wiring point.
 */
@Module
@InstallIn(SingletonComponent::class)
interface RepositoryModule {

    @Binds
    @Singleton
    fun bindOffersRepository(impl: DefaultOffersRepository): OffersRepository
}
