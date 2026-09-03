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
 * - v1: `receipts` (scaffolding shape — never written to by any code path)
 * - v2: added `offers` + `offer_remote_keys` for the offline-first offers feed
 * - v3: replaced `receipts` with the submission outbox; destructive migration removed
 *
 * ## Two kinds of table live here, and that distinction drives everything below
 *
 * | Table | Kind | If a row is lost |
 * |---|---|---|
 * | `offers`, `offer_remote_keys` | **cache** | one network round-trip to rebuild |
 * | `receipts` | **outbox** | a receipt the user submitted is gone forever |
 *
 * Through v2 every table was a cache, so `fallbackToDestructiveMigration(dropAllTables =
 * true)` was the right call: dropping and recreating on a version bump cost nothing that
 * could not be re-fetched, and saved writing migrations nobody would benefit from.
 *
 * v3 ended that. A queued receipt has no upstream copy to restore from — the whole
 * premise of the feature is that the local write is the commit, made while offline, and
 * the server may not learn about it for hours. Destructive migration would delete those
 * rows silently, during a routine app update, with no error surfaced anywhere.
 *
 * So the fallback is gone and [ALL_MIGRATIONS] carries a written path for every version
 * step. The cost is real — a migration per bump, including for the cache tables that
 * would not have needed one — and it is the correct trade the moment one table stops
 * being disposable.
 *
 * **The failure mode this protects against is worth naming**, because it is quiet and
 * generic: a destructive fallback is a standing assertion that *every* table is
 * disposable, and nothing re-checks that assertion when someone later adds a table that
 * is not. With the fallback removed, forgetting a migration throws
 * `IllegalStateException: A migration from N to M was required but not found` on the next
 * launch — loud, immediate, and impossible to ship past. With it, the same mistake ships
 * fine and deletes user data in the field.
 *
 * ## Why receipts share a database with the offers cache
 *
 * **Alternative considered and declined:** a second `RoomDatabase` file, so the
 * disposable cache and the durable outbox have independent lifecycles — the cache could
 * keep its destructive fallback and only the outbox would need careful migrations. That
 * is a genuinely defensible split, and it is the right one if the two ever need different
 * backup, encryption or retention policies.
 *
 * It is declined because the tension it resolves no longer exists: with real migrations
 * everywhere, nothing gets dropped, so there is nothing for the separation to protect.
 * What it would cost is concrete — two `RoomDatabase` instances to provide and open, two
 * migration sets to maintain, and no possibility of a transaction spanning both. Table
 * scoping already gives the isolation that matters: `OffersDao.clearAll()` is `DELETE
 * FROM offers` and cannot reach `receipts`.
 *
 * ## `exportSchema = true`
 *
 * KSP writes a JSON schema per version under `core/data/schemas/`, checked in so schema
 * changes surface in code review. It is now load-bearing rather than merely nice: the DDL
 * in [ALL_MIGRATIONS] is copied from those files, because Room validates the live schema
 * against the entities on open and rejects any mismatch. See the workflow note in
 * `Migrations.kt`.
 */
@Database(
    entities = [
        ReceiptEntity::class,
        OfferEntity::class,
        OfferRemoteKeyEntity::class,
    ],
    version = 3,
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
