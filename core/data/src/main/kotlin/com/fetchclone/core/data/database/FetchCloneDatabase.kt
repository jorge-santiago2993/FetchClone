package com.fetchclone.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fetchclone.core.data.database.dao.OfferRemoteKeysDao
import com.fetchclone.core.data.database.dao.OffersDao
import com.fetchclone.core.data.database.dao.ReceiptDao
import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.database.entity.OfferRemoteKeyEntity
import com.fetchclone.core.data.database.entity.ReceiptEntity

/**
 * The app's Room database.
 *
 * **Version history**
 * - v1: `receipts`
 * - v2: added `offers` + `offer_remote_keys` for the offline-first offers feed.
 *
 * `exportSchema = true` writes a JSON schema per version under
 * `core/data/schemas/`. Those files are checked in so schema changes show up in code
 * review and Room can validate/auto-migrate against them. Because
 * `DatabaseModule` builds this with `fallbackToDestructiveMigration`, bumping the
 * version simply drops and recreates the tables — acceptable for a cache-only
 * database like this one (every row can be re-fetched). A database holding
 * user-authored data would require real `Migration` objects instead.
 */
@Database(
    entities = [
        ReceiptEntity::class,
        OfferEntity::class,
        OfferRemoteKeyEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FetchCloneDatabase : RoomDatabase() {

    abstract fun receiptDao(): ReceiptDao

    abstract fun offersDao(): OffersDao

    abstract fun offerRemoteKeysDao(): OfferRemoteKeysDao

    companion object {
        const val NAME = "fetchclone.db"
    }
}
