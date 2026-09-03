package com.fetchclone.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fetchclone.core.data.database.dao.ReceiptDao
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import com.fetchclone.core.data.model.ReceiptStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Instrumented test for [ReceiptDao] — it exercises the **real SQL**, so it needs a real
 * (in-memory) Room database and runs on a device/emulator.
 *
 * > Run with: `./gradlew :core:data:connectedDebugAndroidTest`
 *
 * ## Why this exists alongside the JVM tests
 *
 * `ReceiptOutboxTest` covers the repository's logic against `FakeReceiptDao`, a Kotlin
 * reimplementation of these queries. That fake is fast and lets tests control the clock,
 * but it can **drift from the SQL it mirrors** — and a fake that silently disagrees with
 * production is worse than no fake at all.
 *
 * This test closes that gap. It pins the guard semantics the whole outbox depends on
 * against the queries that actually ship:
 *
 * - the compare-and-set on every transition, which is the real concurrency control;
 * - `findPending`'s three predicates, including the `attemptCount < :maxAttempts` filter
 *   that makes retry exhaustion terminal;
 * - the SQL-side `attemptCount = attemptCount + 1`, which must be atomic rather than a
 *   read-modify-write;
 * - `countPendingUploads` deliberately *ignoring* `nextAttemptAt`.
 *
 * If one of these fails while the JVM suite passes, the fake has drifted — which is
 * exactly the signal this file is here to give.
 */
class ReceiptDaoTest {

    private val database: FetchCloneDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        FetchCloneDatabase::class.java,
    ).build()

    private val dao: ReceiptDao = database.receiptDao()

    @After
    fun closeDb() = database.close()

    // ---------------------------------------------------------------------------------
    // findPending: the work-selection predicates
    // ---------------------------------------------------------------------------------

    @Test
    fun findPending_selectsQueuedAndFailed_butNotInFlightOrTerminal() = runTest {
        dao.insert(receipt("queued", ReceiptStatusColumn.QUEUED))
        dao.insert(receipt("failed", ReceiptStatusColumn.FAILED))
        dao.insert(receipt("uploading", ReceiptStatusColumn.UPLOADING))
        dao.insert(receipt("processing", ReceiptStatusColumn.PROCESSING))
        dao.insert(receipt("awarded", ReceiptStatusColumn.AWARDED))
        dao.insert(receipt("rejected", ReceiptStatusColumn.REJECTED))

        val pending = dao.findPending(NOW, ReceiptStatus.MAX_UPLOAD_ATTEMPTS).map { it.id }

        assertEquals(setOf("queued", "failed"), pending.toSet())
    }

    @Test
    fun findPending_honoursThePersistedBackoffDeadline() = runTest {
        dao.insert(receipt("due", ReceiptStatusColumn.FAILED, nextAttemptAt = NOW - 1))
        dao.insert(receipt("exactly-now", ReceiptStatusColumn.FAILED, nextAttemptAt = NOW))
        dao.insert(receipt("not-yet", ReceiptStatusColumn.FAILED, nextAttemptAt = NOW + 1))
        // A fresh receipt has no deadline and is eligible immediately.
        dao.insert(receipt("fresh", ReceiptStatusColumn.QUEUED, nextAttemptAt = null))

        val pending = dao.findPending(NOW, ReceiptStatus.MAX_UPLOAD_ATTEMPTS).map { it.id }

        // `<= now` is inclusive; "exactly-now" is due.
        assertEquals(setOf("due", "exactly-now", "fresh"), pending.toSet())
    }

    @Test
    fun findPending_stopsSelectingOnceTheAttemptBudgetIsSpent() = runTest {
        // This single predicate is what makes retry exhaustion terminal without needing a
        // separate FAILED_PERMANENT status. See ReceiptStatus.Failed.
        dao.insert(receipt("one-left", ReceiptStatusColumn.FAILED, attemptCount = 4))
        dao.insert(receipt("spent", ReceiptStatusColumn.FAILED, attemptCount = 5))

        val pending = dao.findPending(NOW, ReceiptStatus.MAX_UPLOAD_ATTEMPTS).map { it.id }

        assertEquals(listOf("one-left"), pending)
    }

    // ---------------------------------------------------------------------------------
    // The compare-and-set guards
    // ---------------------------------------------------------------------------------

    @Test
    fun markUploading_claimsOnce_andSecondClaimantGetsZero() = runTest {
        dao.insert(receipt(ID, ReceiptStatusColumn.QUEUED))

        // This is the outbox's actual concurrency control: two triggers race, exactly one
        // wins. The loser must skip the receipt rather than POST it a second time.
        assertEquals(1, dao.markUploading(ID))
        assertEquals(0, dao.markUploading(ID))
        assertEquals(ReceiptStatusColumn.UPLOADING, dao.findById(ID)!!.status)
    }

    @Test
    fun markProcessing_requiresTheUploadingClaim() = runTest {
        dao.insert(receipt(ID, ReceiptStatusColumn.QUEUED))

        // Without the claim, the transition is refused — a stale pass cannot overwrite a
        // row it does not hold.
        assertEquals(0, dao.markProcessing(ID, serverId = "51"))

        dao.markUploading(ID)
        assertEquals(1, dao.markProcessing(ID, serverId = "51"))
        assertEquals("51", dao.findById(ID)!!.serverId)
    }

    @Test
    fun markProcessing_clearsTheStaleRetryDeadline() = runTest {
        dao.insert(receipt(ID, ReceiptStatusColumn.FAILED, nextAttemptAt = NOW, attemptCount = 2))
        dao.markUploading(ID)

        dao.markProcessing(ID, serverId = "51")

        // The backoff belonged to the upload loop, which this receipt has now left.
        assertNull(dao.findById(ID)!!.nextAttemptAt)
    }

    @Test
    fun markAwarded_onlyAppliesFromProcessing_soALateDuplicatePollCannotReAward() = runTest {
        dao.insert(receipt(ID, ReceiptStatusColumn.PROCESSING, serverId = "51"))

        assertEquals(1, dao.markAwarded(ID, points = 45))
        // A second, later poll response must not land on an already-resolved receipt.
        assertEquals(0, dao.markAwarded(ID, points = 999))
        assertEquals(45, dao.findById(ID)!!.awardedPoints)
    }

    @Test
    fun markRejected_appliesFromBothUploadingAndProcessing() = runTest {
        // A 4xx on the POST rejects from UPLOADING...
        dao.insert(receipt("from-upload", ReceiptStatusColumn.UPLOADING))
        assertEquals(1, dao.markRejected("from-upload", reason = "DUPLICATE"))

        // ...and the server can also reject later, from PROCESSING.
        dao.insert(receipt("from-poll", ReceiptStatusColumn.PROCESSING, serverId = "51"))
        assertEquals(1, dao.markRejected("from-poll", reason = "TOO_OLD"))

        // But never from a terminal or un-sent state.
        dao.insert(receipt("queued", ReceiptStatusColumn.QUEUED))
        assertEquals(0, dao.markRejected("queued", reason = "DUPLICATE"))
    }

    @Test
    fun markRejected_writesNoRetryState() = runTest {
        // The SQL-level expression of "4xx is terminal": the statement simply has no
        // nextAttemptAt and no attemptCount in it, so no retry can be scheduled.
        dao.insert(receipt(ID, ReceiptStatusColumn.QUEUED))
        dao.markUploading(ID)

        dao.markRejected(ID, reason = "DUPLICATE")

        val row = dao.findById(ID)!!
        assertNull(row.nextAttemptAt)
        assertEquals(0, row.attemptCount)
        assertEquals(0, dao.countPendingUploads(ReceiptStatus.MAX_UPLOAD_ATTEMPTS))
    }

    @Test
    fun markFailed_incrementsTheAttemptCountInSql() = runTest {
        dao.insert(receipt(ID, ReceiptStatusColumn.QUEUED, attemptCount = 2))
        dao.markUploading(ID)

        dao.markFailed(ID, nextAttemptAt = NOW + 8_000)

        // Computed by SQLite under the row lock rather than read-modify-written in Kotlin,
        // so two passes cannot both read 2 and both write 3.
        val row = dao.findById(ID)!!
        assertEquals(3, row.attemptCount)
        assertEquals(NOW + 8_000, row.nextAttemptAt)
        assertEquals(ReceiptStatusColumn.FAILED, row.status)
    }

    // ---------------------------------------------------------------------------------
    // Recovery and work reporting
    // ---------------------------------------------------------------------------------

    @Test
    fun requeueStalledUploads_recoversEveryStrandedRow_withoutChargingAnAttempt() = runTest {
        dao.insert(receipt("stranded-a", ReceiptStatusColumn.UPLOADING, attemptCount = 1))
        dao.insert(receipt("stranded-b", ReceiptStatusColumn.UPLOADING, attemptCount = 3))
        dao.insert(receipt("untouched", ReceiptStatusColumn.PROCESSING, serverId = "51"))

        assertEquals(2, dao.requeueStalledUploads())

        assertEquals(ReceiptStatusColumn.QUEUED, dao.findById("stranded-a")!!.status)
        // A process death is not the receipt's fault; a crash loop must not burn the budget.
        assertEquals(1, dao.findById("stranded-a")!!.attemptCount)
        assertEquals(ReceiptStatusColumn.PROCESSING, dao.findById("untouched")!!.status)
    }

    @Test
    fun releaseUploadClaim_targetsOneRowOnly() = runTest {
        dao.insert(receipt("cancelled", ReceiptStatusColumn.UPLOADING))
        dao.insert(receipt("still-going", ReceiptStatusColumn.UPLOADING))

        assertEquals(1, dao.releaseUploadClaim("cancelled"))

        assertEquals(ReceiptStatusColumn.QUEUED, dao.findById("cancelled")!!.status)
        assertEquals(ReceiptStatusColumn.UPLOADING, dao.findById("still-going")!!.status)
    }

    @Test
    fun countPendingUploads_countsBackedOffReceipts_butNotProcessingOnes() = runTest {
        // Waiting out a backoff is still outstanding work — the worker must reschedule.
        dao.insert(receipt("backed-off", ReceiptStatusColumn.FAILED, nextAttemptAt = NOW + 60_000))
        dao.insert(receipt("queued", ReceiptStatusColumn.QUEUED))
        // PROCESSING is resolved by reconciliation, which the worker does not run; counting
        // it would make the worker reschedule itself forever.
        dao.insert(receipt("processing", ReceiptStatusColumn.PROCESSING, serverId = "51"))
        dao.insert(receipt("spent", ReceiptStatusColumn.FAILED, attemptCount = 5))

        assertEquals(2, dao.countPendingUploads(ReceiptStatus.MAX_UPLOAD_ATTEMPTS))
    }

    // ---------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------

    @Test
    fun observeAll_ordersByCaptureTimeDescending() = runTest {
        dao.insert(receipt("older", ReceiptStatusColumn.QUEUED, capturedAt = NOW - 10_000))
        dao.insert(receipt("newest", ReceiptStatusColumn.QUEUED, capturedAt = NOW))
        dao.insert(receipt("middle", ReceiptStatusColumn.QUEUED, capturedAt = NOW - 5_000))

        val ids = dao.observeAll().first().map { it.id }

        // Chronological history: a receipt must not jump position as it uploads.
        assertEquals(listOf("newest", "middle", "older"), ids)
    }

    @Test
    fun findProcessing_excludesRowsWithNoServerId() = runTest {
        dao.insert(receipt("reconcilable", ReceiptStatusColumn.PROCESSING, serverId = "51"))
        // Would be a bug if it existed; the filter means reconciliation is never handed a
        // receipt it has no way to look up.
        dao.insert(receipt("malformed", ReceiptStatusColumn.PROCESSING, serverId = null))

        assertEquals(listOf("reconcilable"), dao.findProcessing().map { it.id })
    }

    private fun receipt(
        id: String,
        status: String,
        attemptCount: Int = 0,
        nextAttemptAt: Long? = null,
        serverId: String? = null,
        capturedAt: Long = NOW,
    ) = ReceiptEntity(
        id = id,
        capturedAt = capturedAt,
        lineItemsJson = LINE_ITEMS_JSON,
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        serverId = serverId,
    )

    private companion object {
        const val ID = "11111111-1111-1111-1111-111111111111"
        const val NOW = 1_756_800_000_000L
        const val LINE_ITEMS_JSON =
            """[{"productId":1,"title":"Red Lipstick","quantity":2,"unitPriceCents":1299,"discountPercentage":40.0}]"""
    }
}
