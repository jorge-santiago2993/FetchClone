package com.fetchclone.core.data.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FetchCloneDatabase =
        Room.databaseBuilder(context, FetchCloneDatabase::class.java, FetchCloneDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
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
