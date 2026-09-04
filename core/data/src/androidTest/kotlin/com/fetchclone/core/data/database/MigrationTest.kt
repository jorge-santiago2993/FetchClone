package com.fetchclone.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetchclone.core.data.database.entity.ORPHANED_USER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Exercises the hand-written migrations against the real exported schemas.
 *
 * ## Why this file exists, and why it arrived with `MIGRATION_3_4`
 *
 * The earlier migrations were, in effect, self-testing: `receipts` was scaffolding nobody
 * had written to, so `MIGRATION_2_3` could drop and recreate the table and the worst
 * possible outcome was losing rows that did not exist. That claim is recorded on the
 * migration itself, and it stopped being true the moment the outbox shipped.
 *
 * `MIGRATION_3_4` runs against a table holding **un-uploaded user data** — receipts the app
 * has already told the user were saved, which exist nowhere else. There is no
 * `fallbackToDestructiveMigration` to catch a mistake, deliberately, so an untested
 * migration is the single most dangerous piece of code in the module: it runs exactly once
 * per install, on a device that already has the data, and a bug either loses it or
 * crashes the app on launch with no recovery.
 *
 * Room validates the resulting schema itself when it opens the database, which catches a
 * *structural* mistake. What it cannot check is whether the data survived, and that is what
 * these tests are for.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FetchCloneDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrate3To4_preservesExistingReceipts() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            // A receipt captured before authentication existed: queued, one failed attempt,
            // a backoff armed. Exactly the row that must not be lost.
            db.execSQL(
                """
                INSERT INTO receipts
                    (id, capturedAt, lineItemsJson, status, attemptCount, nextAttemptAt, serverId, awardedPoints, rejectReason)
                VALUES
                    ('pre-auth-1', 1700000000000, '$LINE_ITEMS_JSON', 'FAILED', 1, 1700000060000, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT id, status, attemptCount, nextAttemptAt, userId FROM receipts").use { cursor ->
            assertTrue("the pre-auth receipt must survive the upgrade", cursor.moveToFirst())
            assertEquals("pre-auth-1", cursor.getString(0))

            // Every column carried over, not just the primary key. An ALTER TABLE that
            // silently reset the retry state would look like a successful migration and
            // quietly restart every backoff on the device.
            assertEquals("FAILED", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(1_700_000_060_000L, cursor.getLong(3))

            // The new column takes its declared default. This value matches no real
            // account, so the row is invisible to every per-user query -- stranded rather
            // than misattributed, which is the deliberate trade documented on the
            // migration.
            assertEquals(ORPHANED_USER_ID, cursor.getInt(4))

            assertEquals("exactly one row expected", 1, cursor.count)
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_addsTheUserIdIndex() {
        helper.createDatabase(TEST_DB, 3).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Room's own validation covers this, but asserting it directly is worth the three
        // lines: forgetting the index does not break correctness, so the app would run
        // fine while every per-user query degraded to a full table scan on a table that
        // only grows. That is the kind of regression nobody notices until it is slow.
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'receipts'")
            .use { cursor ->
                val indices = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertTrue("index_receipts_userId missing: $indices", "index_receipts_userId" in indices)
                assertTrue("index_receipts_status missing: $indices", "index_receipts_status" in indices)
            }
    }

    @Test
    @Throws(IOException::class)
    fun migratesAllTheWayFromVersion1() {
        // The path a device that has not been updated in a while actually takes. Testing
        // only the newest step would miss a break in an earlier one, and with no
        // destructive fallback there is no safety net for a version transition that has no
        // written path -- Room simply throws on open.
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS)

        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            val tables = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue("receipts missing: $tables", "receipts" in tables)
            assertTrue("offers missing: $tables", "offers" in tables)
            assertTrue("offer_remote_keys missing: $tables", "offer_remote_keys" in tables)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val LINE_ITEMS_JSON =
            """[{"productId":1,"title":"Red Lipstick","quantity":2,"unitPriceCents":1299,"discountPercentage":40.0}]"""
    }
}
