package com.fetchclone.core.data.di

import android.content.Context
import androidx.room.Room
import com.fetchclone.core.data.database.ALL_MIGRATIONS
import com.fetchclone.core.data.database.FetchCloneDatabase
import com.fetchclone.core.data.database.dao.OfferRemoteKeysDao
import com.fetchclone.core.data.database.dao.OffersDao
import com.fetchclone.core.data.database.dao.ReceiptDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Builds the database with explicit migrations and **no destructive fallback**.
     *
     * The missing `fallbackToDestructiveMigration(...)` is the load-bearing part of this
     * method. It used to be here, and removing it was a deliberate consequence of
     * `receipts` becoming a submission outbox rather than a cache — see the table-kinds
     * discussion on [FetchCloneDatabase].
     *
     * The practical effect: bumping the schema version without adding a matching entry to
     * [ALL_MIGRATIONS] now throws on the next launch instead of silently dropping every
     * table. That is the behaviour we want. A crash on a developer's device during the
     * change that caused it is cheap; discovering in production that an update deleted
     * users' un-uploaded receipts is not.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FetchCloneDatabase =
        Room.databaseBuilder(context, FetchCloneDatabase::class.java, FetchCloneDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideReceiptDao(database: FetchCloneDatabase): ReceiptDao = database.receiptDao()

    // DAOs are provided individually so consumers/tests can depend on just the slice
    // of the DB they need. `DefaultOffersRepository` still takes the whole
    // FetchCloneDatabase because its RemoteMediator needs `database.withTransaction`,
    // which is only on the RoomDatabase instance.
    @Provides
    fun provideOffersDao(database: FetchCloneDatabase): OffersDao = database.offersDao()

    @Provides
    fun provideOfferRemoteKeysDao(database: FetchCloneDatabase): OfferRemoteKeysDao =
        database.offerRemoteKeysDao()
}
