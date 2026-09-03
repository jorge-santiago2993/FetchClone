package com.fetchclone.core.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written schema migrations for [FetchCloneDatabase].
 *
 * ## Why this file exists at all
 *
 * Until the receipt outbox landed, this database was configured with
 * `fallbackToDestructiveMigration(dropAllTables = true)` and had no migrations. That was
 * a defensible call while every table was a cache: dropping `offers` costs one network
 * round-trip to rebuild, so the engineering time to write and test a migration bought
 * nothing.
 *
 * `receipts` broke that argument. A queued receipt is not re-fetchable — it may be the
 * only copy in existence of something the user did and we told them had succeeded.
 * Destructive migration would delete it silently, on an app update, with no error and no
 * way to recover. So the fallback is gone and every version step is written out.
 *
 * The lesson generalises past this app: **a destructive fallback is a statement that
 * every table is disposable.** It stays true only until someone adds a table that is not,
 * and nothing about adding that table forces anyone to revisit the database builder. That
 * is the trap. The guard against it is on [FetchCloneDatabase]: with no fallback, adding
 * a table and bumping the version *without* writing a migration fails loudly at runtime
 * instead of quietly eating data.
 *
 * ## Why the offers cache gets a real migration too
 *
 * `MIGRATION_1_2` recreates tables that are pure cache and could safely have been
 * dropped. It exists because removing the fallback is all-or-nothing: Room needs a path
 * for **every** version transition a device in the field might make, not just the
 * interesting one. A device still on v1 that updates straight to v3 runs 1→2 then 2→3,
 * and a missing 1→2 would crash it on launch with `IllegalStateException: A migration
 * from 1 to 3 was required but not found`.
 *
 * A tidier alternative for a cache-only step is `fallbackToDestructiveMigrationFrom(1)`,
 * which keeps the fallback for named versions only. It is declined here because writing
 * the migration is a dozen lines and the version-specific opt-out is one more piece of
 * configuration to keep correct as versions accumulate.
 *
 * ## Where this DDL came from — do not hand-write it
 *
 * Every `CREATE TABLE` below is copied verbatim from the exported schema JSON under
 * `core/data/schemas/`, with `${'$'}{TABLE_NAME}` substituted for the real name. That is
 * not fussiness: Room validates the live schema against the entity definitions on open
 * and throws if they differ **in any detail** — a missing backtick, `INTEGER` where it
 * wanted `TEXT`, a nullable column declared `NOT NULL`. Retyping the DDL from the Kotlin
 * entity is how migrations end up crashing only on the upgrade path, which is exactly the
 * path that is hardest to test and most expensive to get wrong.
 *
 * The workflow to repeat when adding a version: change the entity, bump
 * [FetchCloneDatabase]'s version, build once so KSP exports the new schema JSON, then
 * copy that file's `createSql` into a new `Migration` here.
 */

/**
 * v1 → v2: adds the offers feed's two tables.
 *
 * `receipts` is untouched — at v2 it was still the unused scaffolding table.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `offers` (
                `id` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `brand` TEXT,
                `priceCents` INTEGER NOT NULL,
                `discountPercentage` REAL NOT NULL,
                `rating` REAL NOT NULL,
                `stock` INTEGER NOT NULL,
                `thumbnailUrl` TEXT NOT NULL,
                `feedPosition` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `offer_remote_keys` (
                `offerId` INTEGER NOT NULL,
                `prevKey` INTEGER,
                `nextKey` INTEGER,
                PRIMARY KEY(`offerId`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * v2 → v3: replaces the scaffolding `receipts` table with the submission outbox.
 *
 * ### Why this one drops instead of altering, and why that is safe *here specifically*
 *
 * The v1/v2 `receipts` table (`merchant`, `totalCents`, `pointsEarned`,
 * `purchasedAtEpochMillis`) shares exactly one column with the outbox schema: `id`. There
 * is no sensible column-by-column mapping — the two tables model different things that
 * happen to share a name.
 *
 * `DROP TABLE` is acceptable **only because that table was never written to**: no code
 * path ever inserted into it, so every device's copy is provably empty. That is a fact
 * about this repository's history, not a general licence, and it is the whole
 * justification for this migration being three lines instead of a data-preserving
 * rewrite. Had users' receipts been in there, the correct shape would have been the
 * standard create-new / `INSERT INTO ... SELECT` / drop-old / rename dance, and the
 * unmappable columns would have needed a product decision rather than a technical one.
 *
 * Stating the assumption out loud matters more than the SQL does: "this migration is safe
 * because the table is empty" is a claim that stops being true the moment someone writes
 * to it, and the next person to read this needs to know the claim was made.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `receipts`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `receipts` (
                `id` TEXT NOT NULL,
                `capturedAt` INTEGER NOT NULL,
                `lineItemsJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `attemptCount` INTEGER NOT NULL,
                `nextAttemptAt` INTEGER,
                `serverId` TEXT,
                `awardedPoints` INTEGER,
                `rejectReason` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        // Indices are a separate statement and are easy to forget, because forgetting one
        // does not break correctness — the app runs fine and `findPending` just degrades
        // to a full table scan on a table that only grows. Room's schema validation is
        // what catches it: the index is part of the schema it compares on open.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_status` ON `receipts` (`status`)")
    }
}

/** Every migration, in the order Room should consider them. Passed to `addMigrations`. */
internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
