package com.fetchclone.core.data.repository

import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.model.RejectReason
import com.fetchclone.core.data.receipt.FakeAuthRepository
import com.fetchclone.core.data.receipt.FakeCartsApi
import com.fetchclone.core.data.receipt.FakeNetworkMonitor
import com.fetchclone.core.data.receipt.FakeOffersDao
import com.fetchclone.core.data.receipt.FakeReceiptDao
import com.fetchclone.core.data.receipt.MutableTestClock
import com.fetchclone.core.data.receipt.ReceiptOutcome
import com.fetchclone.core.data.receipt.ReceiptProcessor
import com.fetchclone.core.data.receipt.ReceiptScanner
import com.fetchclone.core.data.receipt.RecordingOutboxSyncScheduler
import com.fetchclone.core.data.receipt.RetryBackoff
import com.fetchclone.core.data.receipt.TEST_USER_ID
import com.fetchclone.core.data.receipt.httpException
import com.fetchclone.core.data.receipt.ioException
import com.fetchclone.core.data.network.model.CartResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox state machine, end to end through [DefaultReceiptRepository].
 *
 * Covers the three areas the spec names — **state transitions**, **backoff**, and **that a
 * 4xx does not schedule a retry** — plus retry exhaustion and crash recovery.
 *
 * ## Why these assert on database rows rather than on return values
 *
 * The outbox's contract *is* the row: what the UI renders, what the next pass selects, and
 * what survives process death all come from `receipts`. A test that checked
 * `processQueue()`'s boolean would pass while writing entirely the wrong status. So every
 * assertion below reads the row back through [FakeReceiptDao] and checks the exact columns
 * the next actor depends on — including, for the 4xx case, the columns that must *not* have
 * been written.
 *
 * ## Why `StandardTestDispatcher` and an injected clock, together
 *
 * They control different things and both are needed. The dispatcher decides *when
 * coroutines run* — `submitSimulatedScan` launches its upload into an application scope, so
 * without `advanceUntilIdle()` the test would assert before that coroutine had started.
 * [MutableTestClock] decides *what time it is* — nothing here `delay`s for a backoff, the
 * schedule is a timestamp compared against now, so making a receipt due means moving the
 * clock, not advancing the dispatcher.
 *
 * This is exactly what the spec's "inject the `CoroutineDispatcher`" constraint buys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptOutboxTest {

    private val dao = FakeReceiptDao()
    private val api = FakeCartsApi()
    private val scheduler = RecordingOutboxSyncScheduler()
    private val clock = MutableTestClock(nowMillis = NOW)
    private val networkMonitor = FakeNetworkMonitor(online = true)
    private val authRepository = FakeAuthRepository()
    private var processorOutcome: ReceiptOutcome = ReceiptOutcome.StillProcessing

    // ---------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------

    @Test
    fun `submit writes the receipt as QUEUED and returns without touching the network`() = runTest {
        val repository = repository(scope = this)

        val result = repository.submitSimulatedScan()

        // The local write IS the commit: by the time submit returns, the row exists.
        val receiptId = (result as SubmitResult.Success).receiptId
        val row = dao.row(receiptId)!!
        assertEquals(ReceiptStatusColumn.QUEUED, row.status)
        assertEquals(0, row.attemptCount)
        // Null nextAttemptAt is what makes it immediately eligible in findPending.
        assertNull(row.nextAttemptAt)

        // No POST has happened yet — the upload was only *launched*, into a scope this
        // test has not yet run. That gap is the whole point of the design.
        assertTrue(api.idempotencyKeys.isEmpty())
    }

    @Test
    fun `submit triggers both the immediate upload and the durable worker`() = runTest {
        val repository = repository(scope = this)

        repository.submitSimulatedScan()
        advanceUntilIdle() // let the application-scoped upload run

        // Trigger 1: the in-process upload actually ran.
        assertEquals(1, api.idempotencyKeys.size)
        // Trigger 3: WorkManager was asked for the durable fallback too. Both, not either
        // — see OutboxSyncScheduler for why they are not redundant.
        assertEquals(1, scheduler.scheduleCount)
    }

    @Test
    fun `a successful upload moves QUEUED to PROCESSING and stores the server id`() = runTest {
        api.addCartResponder = { _, _ -> CartResponse(id = 77) }
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.PROCESSING, row.status)
        assertEquals("77", row.serverId)
    }

    @Test
    fun `reconciliation moves PROCESSING to AWARDED with the points from the processor`() = runTest {
        dao.seed(processingReceipt(ID))
        processorOutcome = ReceiptOutcome.Awarded(points = 45)

        repository(scope = this).reconcileProcessing()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.AWARDED, row.status)
        assertEquals(45, row.awardedPoints)
    }

    @Test
    fun `reconciliation leaves a still-processing receipt untouched`() = runTest {
        dao.seed(processingReceipt(ID))
        processorOutcome = ReceiptOutcome.StillProcessing

        repository(scope = this).reconcileProcessing()

        assertEquals(ReceiptStatusColumn.PROCESSING, dao.row(ID)!!.status)
    }

    @Test
    fun `reconciliation can also reject, and that is terminal`() = runTest {
        dao.seed(processingReceipt(ID))
        processorOutcome = ReceiptOutcome.Rejected(RejectReason.DUPLICATE)

        val repository = repository(scope = this)
        repository.reconcileProcessing()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.REJECTED, row.status)
        assertEquals(RejectReason.DUPLICATE.name, row.rejectReason)

        // Terminal: a later upload pass must not pick it back up.
        repository.processQueue()
        assertEquals(ReceiptStatusColumn.REJECTED, dao.row(ID)!!.status)
    }

    // ---------------------------------------------------------------------------------
    // 4xx is terminal -- explicitly required by the spec
    // ---------------------------------------------------------------------------------

    @Test
    fun `a 4xx rejects terminally and schedules NO retry`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(409) }
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.REJECTED, row.status)
        assertEquals(RejectReason.DUPLICATE.name, row.rejectReason)

        // The three assertions that actually encode "no retry was scheduled":
        assertNull("a rejected receipt must not have a retry armed", row.nextAttemptAt)
        assertEquals("a rejection must not consume an attempt", 0, row.attemptCount)
        assertEquals(
            "a rejected receipt must not be reported as outstanding work",
            0,
            dao.countPendingUploads(TEST_USER_ID, ReceiptStatus.MAX_UPLOAD_ATTEMPTS),
        )
    }

    @Test
    fun `a rejected receipt is never selected again, however far time advances`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(400) }
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        repository.processQueue()
        assertEquals(1, api.idempotencyKeys.size)

        // Jump a week. A retryable failure would be long due by now; a terminal one is not
        // a matter of time at all.
        clock.advanceBy(7 * 24 * 60 * 60 * 1000L)
        repository.processQueue()

        assertEquals("no second POST for a rejected receipt", 1, api.idempotencyKeys.size)
    }

    @Test
    fun `each 4xx maps to its documented reject reason`() = runTest {
        // The mapping table from `classifyUploadFailure`, pinned. These pairings are
        // invented (DummyJSON defines none), so this test is what stops them drifting.
        val cases = listOf(
            409 to RejectReason.DUPLICATE,
            410 to RejectReason.TOO_OLD,
            422 to RejectReason.UNREADABLE,
            400 to RejectReason.NOT_A_RECEIPT,
            418 to RejectReason.UNREADABLE, // an unmapped 4xx falls back
        )
        val repository = repository(scope = this)

        for ((status, expected) in cases) {
            val id = "receipt-$status"
            dao.seed(queuedReceipt(id))
            api.addCartResponder = { _, _ -> throw httpException(status) }

            repository.processQueue()

            assertEquals("HTTP $status", expected.name, dao.row(id)!!.rejectReason)
        }
    }

    // ---------------------------------------------------------------------------------
    // 5xx / IO are retryable
    // ---------------------------------------------------------------------------------

    @Test
    fun `a 5xx fails retryably, counts an attempt and arms the persisted backoff`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(503) }
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.FAILED, row.status)
        assertEquals(1, row.attemptCount)
        assertNull("a retryable failure is not a rejection", row.rejectReason)

        // The backoff is a persisted absolute deadline, not an in-memory timer. First
        // failure => 2^1 * 1000ms, plus 0-1000ms of jitter.
        val delay = row.nextAttemptAt!! - NOW
        assertTrue("expected ~2s backoff, got ${delay}ms", delay in 2_000..3_000)
    }

    @Test
    fun `an IO failure is retryable too, and not conflated with a rejection`() = runTest {
        api.addCartResponder = { _, _ -> throw ioException() }
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.FAILED, row.status)
        assertNotNull(row.nextAttemptAt)
    }

    @Test
    fun `a failed receipt is not retried before its backoff expires`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(500) }
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        repository.processQueue()
        assertEquals(1, api.idempotencyKeys.size)

        // Still inside the backoff window: findPending must not select it.
        clock.advanceBy(1_000)
        repository.processQueue()
        assertEquals("retried too early", 1, api.idempotencyKeys.size)

        // Past the window: now it is due.
        clock.advanceBy(5_000)
        repository.processQueue()
        assertEquals(2, api.idempotencyKeys.size)
    }

    @Test
    fun `the idempotency key is the receipt id and never changes across retries`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(500) }
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        // Drive several attempts, stepping past each backoff.
        repeat(3) {
            repository.processQueue()
            clock.advanceBy(RetryBackoff.MAX_DELAY_MILLIS)
        }

        // A fresh key per attempt is the classic way to silently disable idempotency: the
        // code looks right and the server sees N distinct submissions.
        assertEquals(3, api.idempotencyKeys.size)
        assertEquals(listOf(ID, ID, ID), api.idempotencyKeys)
    }

    @Test
    fun `a retry that succeeds moves the receipt on to PROCESSING`() = runTest {
        var failuresLeft = 2
        api.addCartResponder = { _, _ ->
            if (failuresLeft-- > 0) throw httpException(500)
            CartResponse(id = 99)
        }
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        repeat(3) {
            repository.processQueue()
            clock.advanceBy(RetryBackoff.MAX_DELAY_MILLIS)
        }

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.PROCESSING, row.status)
        assertEquals("99", row.serverId)
        // markProcessing clears the stale retry deadline; a leftover would be a dead field.
        assertNull(row.nextAttemptAt)
    }

    // ---------------------------------------------------------------------------------
    // Exhaustion
    // ---------------------------------------------------------------------------------

    @Test
    fun `retries stop at the attempt budget and the receipt becomes terminal`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(500) }
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        // Run well past the budget, always stepping clear of the backoff.
        repeat(ReceiptStatus.MAX_UPLOAD_ATTEMPTS + 4) {
            repository.processQueue()
            clock.advanceBy(RetryBackoff.MAX_DELAY_MILLIS + 1)
        }

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatus.MAX_UPLOAD_ATTEMPTS, row.attemptCount)
        assertEquals(
            "attempts must stop exactly at the budget",
            ReceiptStatus.MAX_UPLOAD_ATTEMPTS,
            api.idempotencyKeys.size,
        )
        // Status stays FAILED; exhaustion is derived from the counter, not a new status.
        assertEquals(ReceiptStatusColumn.FAILED, row.status)
        assertEquals(0, dao.countPendingUploads(TEST_USER_ID, ReceiptStatus.MAX_UPLOAD_ATTEMPTS))
    }

    @Test
    fun `an exhausted receipt surfaces to the UI as a terminal failure`() = runTest {
        dao.seed(
            queuedReceipt(ID).copy(
                status = ReceiptStatusColumn.FAILED,
                attemptCount = ReceiptStatus.MAX_UPLOAD_ATTEMPTS,
                nextAttemptAt = NOW,
            ),
        )

        val receipt = firstReceipt(repository(scope = this))
        val status = receipt.status as ReceiptStatus.Failed

        // The badge reads this, not a constant of its own -- see ReceiptStatusBadge.
        assertTrue(status.isExhausted)
    }

    // ---------------------------------------------------------------------------------
    // No connectivity is NOT a failed attempt
    //
    // Regression tests for a bug reproduced on a device: airplane mode + six app
    // foregrounds burned all five attempts and left a valid receipt permanently
    // undeliverable. See NetworkMonitor and UploadFailure.Deferred.
    // ---------------------------------------------------------------------------------

    @Test
    fun `an offline pass makes no request at all`() = runTest {
        networkMonitor.online = false
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        // Not "the request failed" — the request was never made. Skipping the pass up front
        // is what stops the radio being woken to discover a fact we already knew.
        assertTrue("no POST should be attempted with no network", api.idempotencyKeys.isEmpty())
    }

    @Test
    fun `an offline pass leaves the receipt exactly as it found it`() = runTest {
        networkMonitor.online = false
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals("must stay QUEUED, not FAILED", ReceiptStatusColumn.QUEUED, row.status)
        assertEquals("no attempt may be consumed", 0, row.attemptCount)
        assertNull("no backoff should be armed", row.nextAttemptAt)
    }

    @Test
    fun `repeated offline passes never exhaust the attempt budget`() = runTest {
        // The exact reproduction: on device this was six app foregrounds in airplane mode,
        // which burned all five attempts and killed the receipt for good.
        networkMonitor.online = false
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        repeat(ReceiptStatus.MAX_UPLOAD_ATTEMPTS * 3) {
            repository.processQueue()
            clock.advanceBy(RetryBackoff.MAX_DELAY_MILLIS)
        }

        assertEquals(0, api.idempotencyKeys.size)
        assertEquals(0, dao.row(ID)!!.attemptCount)
        assertEquals(ReceiptStatusColumn.QUEUED, dao.row(ID)!!.status)
    }

    @Test
    fun `the receipt uploads normally once connectivity returns`() = runTest {
        networkMonitor.online = false
        dao.seed(queuedReceipt(ID))
        val repository = repository(scope = this)

        repeat(5) { repository.processQueue() }
        assertEquals(ReceiptStatusColumn.QUEUED, dao.row(ID)!!.status)

        // What the CONNECTED-constrained worker does when the OS wakes it.
        networkMonitor.online = true
        repository.processQueue()

        assertEquals(ReceiptStatusColumn.PROCESSING, dao.row(ID)!!.status)
        assertEquals(1, api.idempotencyKeys.size)
    }

    @Test
    fun `an offline pass keeps a durable worker armed`() = runTest {
        networkMonitor.online = false
        dao.seed(queuedReceipt(ID))

        val workRemains = repository(scope = this).processQueue()

        // Nothing in-process will retry while offline, so the OS-held job is the only path
        // back. It must exist.
        assertTrue(workRemains)
        assertTrue("worker must be enqueued", scheduler.scheduleCount >= 1)
    }

    @Test
    fun `connectivity lost mid-request costs the receipt nothing`() = runTest {
        // Passed the pre-flight check, then the train entered a tunnel. The post-hoc check
        // in uploadOne is what catches this window.
        dao.seed(queuedReceipt(ID))
        api.addCartResponder = { _, _ ->
            networkMonitor.online = false
            throw ioException()
        }

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.QUEUED, row.status)
        assertEquals(0, row.attemptCount)
    }

    @Test
    fun `an IO failure while still online DOES count as an attempt`() = runTest {
        // The other half of the split: a timeout on a working connection is real evidence
        // about this delivery, so it is fair to spend an attempt on it.
        dao.seed(queuedReceipt(ID))
        api.addCartResponder = { _, _ -> throw ioException() }

        repository(scope = this).processQueue()

        val row = dao.row(ID)!!
        assertEquals(ReceiptStatusColumn.FAILED, row.status)
        assertEquals(1, row.attemptCount)
        assertNotNull(row.nextAttemptAt)
    }

    @Test
    fun `a failed pass re-arms the worker even without a new submit`() = runTest {
        // scheduleUpload() used to be called only from submit(), so a receipt failed by a
        // foreground pass could be left with no pending job to retry it.
        api.addCartResponder = { _, _ -> throw httpException(500) }
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        assertTrue("worker must be re-armed", scheduler.scheduleCount >= 1)
    }

    @Test
    fun `a fully drained queue does not re-arm the worker`() = runTest {
        dao.seed(queuedReceipt(ID))

        repository(scope = this).processQueue()

        // Nothing left to do, so no job should be left pending burning scheduler slots.
        assertEquals(0, scheduler.scheduleCount)
    }

    // ---------------------------------------------------------------------------------
    // Crash recovery and work reporting
    // ---------------------------------------------------------------------------------

    @Test
    fun `an upload stranded by process death is requeued and retried`() = runTest {
        // A row left UPLOADING by a process that died mid-request. Invisible to
        // findPending, so without recovery it would be silently lost forever.
        dao.seed(queuedReceipt(ID).copy(status = ReceiptStatusColumn.UPLOADING))

        repository(scope = this).processQueue()

        assertEquals("the stranded receipt was never re-sent", 1, api.idempotencyKeys.size)
        assertEquals(ReceiptStatusColumn.PROCESSING, dao.row(ID)!!.status)
    }

    @Test
    fun `crash recovery does not consume an attempt`() = runTest {
        dao.seed(
            queuedReceipt(ID).copy(status = ReceiptStatusColumn.UPLOADING, attemptCount = 2),
        )

        repository(scope = this).processQueue()

        // A process death is not the receipt's fault; charging it would let a crash loop
        // burn the user's retry budget on a receipt the server may never have seen.
        assertEquals(2, dao.row(ID)!!.attemptCount)
    }

    @Test
    fun `processQueue reports outstanding work so the worker reschedules`() = runTest {
        api.addCartResponder = { _, _ -> throw httpException(500) }
        dao.seed(queuedReceipt(ID))

        val workRemains = repository(scope = this).processQueue()

        // True even though the receipt is now mid-backoff and not currently due. If this
        // returned false, WorkManager would report success and forget the outbox until
        // some other trigger happened to fire.
        assertTrue(workRemains)
    }

    @Test
    fun `processQueue reports no work once the queue is drained`() = runTest {
        dao.seed(queuedReceipt(ID))
        assertEquals(false, repository(scope = this).processQueue())
    }

    @Test
    fun `submit reports NoOffersCached when the offers cache is empty`() = runTest {
        // A real first-launch case: the user opens Receipts before Offers. Not an error,
        // so it is a typed result rather than an exception.
        val repository = repository(scope = this, offers = emptyList())

        assertEquals(SubmitResult.NoOffersCached, repository.submitSimulatedScan())
        assertTrue(dao.allRows().isEmpty())
    }

    @Test
    fun `the receipts stream maps entities to the domain state machine`() = runTest {
        dao.seed(processingReceipt(ID))

        val receipt = firstReceipt(repository(scope = this))

        assertEquals(ReceiptStatus.Processing, receipt.status)
        assertEquals(ID, receipt.id)
        // Line items survive the JSON round trip, including the snapshotted discount that
        // the award depends on.
        assertEquals(1, receipt.lineItems.size)
        assertEquals(40.0, receipt.lineItems.first().discountPercentage, 0.001)
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private suspend fun firstReceipt(repository: ReceiptRepository): Receipt =
        repository.observeReceipts().first().first()

    private fun repository(
        scope: TestScope,
        offers: List<OfferEntity> = listOf(offer()),
        auth: FakeAuthRepository = authRepository,
    ) = DefaultReceiptRepository(
        receiptDao = dao,
        cartsApi = api,
        authRepository = auth,
        scanner = ReceiptScanner(FakeOffersDao(offers)),
        networkMonitor = networkMonitor,
        processor = StubProcessor { processorOutcome },
        scheduler = scheduler,
        clock = clock,
        // The injected dispatcher the spec asks for. `scope.testScheduler` shares the
        // test's virtual clock, so `advanceUntilIdle()` drives work launched here.
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        applicationScope = scope,
    )

    private fun queuedReceipt(id: String, userId: Int = TEST_USER_ID) = ReceiptEntity(
        id = id,
        userId = userId,
        capturedAt = NOW,
        lineItemsJson = LINE_ITEMS_JSON,
        status = ReceiptStatusColumn.QUEUED,
    )

    private fun processingReceipt(id: String) =
        queuedReceipt(id).copy(status = ReceiptStatusColumn.PROCESSING, serverId = "51")

    private fun offer() = OfferEntity(
        id = 1,
        title = "Red Lipstick",
        description = "",
        category = "beauty",
        brand = "Chic",
        priceCents = 1_299,
        discountPercentage = 40.0,
        rating = 4.4,
        stock = 10,
        thumbnailUrl = "",
        feedPosition = 0,
    )

    private class StubProcessor(private val outcome: () -> ReceiptOutcome) : ReceiptProcessor {
        override suspend fun poll(receipt: Receipt): ReceiptOutcome = outcome()
    }

    private companion object {
        const val ID = "11111111-1111-1111-1111-111111111111"
        const val NOW = 1_756_800_000_000L
        const val LINE_ITEMS_JSON =
            """[{"productId":1,"title":"Red Lipstick","quantity":2,"unitPriceCents":1299,"discountPercentage":40.0}]"""
    }
}
