package com.fetchclone.core.data.repository

import com.fetchclone.core.data.database.entity.ORPHANED_USER_ID
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import com.fetchclone.core.data.model.ReceiptStatus
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
import com.fetchclone.core.data.receipt.TEST_USER_ID
import com.fetchclone.core.data.receipt.httpException
import com.fetchclone.core.data.model.Receipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox's behaviour once receipts have owners and uploads need a session.
 *
 * ## The bug these tests exist to prevent
 *
 * `classifyUploadFailure` maps every 4xx to a terminal `REJECTED`. A 401 is a 4xx. So
 * without a dedicated branch, a session expiring mid-upload would mark a perfectly good
 * receipt **permanently rejected** and tell the user their receipt was refused — a false,
 * irreversible verdict about their shopping, caused by their token running out.
 *
 * That is the airplane-mode bug in a different costume: a problem that has nothing to do
 * with the receipt, charged to the receipt. `UploadFailure` predicted it in a comment long
 * before auth existed, which is a decent argument for writing down the exceptions you are
 * choosing not to handle yet.
 */
class ReceiptOutboxAuthTest {

    private val dao = FakeReceiptDao()
    private val api = FakeCartsApi()
    private val scheduler = RecordingOutboxSyncScheduler()
    private val clock = MutableTestClock(nowMillis = NOW)
    private val networkMonitor = FakeNetworkMonitor(online = true)
    private val authRepository = FakeAuthRepository()

    @Test
    fun `a 401 does not reject the receipt and does not spend an attempt`() = runTest {
        dao.seed(queuedReceipt("r1"))
        api.addCartResponder = { _, _ -> throw httpException(401) }

        repository(this).processQueue()

        val row = requireNotNull(dao.row("r1"))
        // NOT REJECTED. This is the whole test.
        assertEquals(ReceiptStatusColumn.QUEUED, row.status)
        // ...and the receipt keeps its full retry budget, because a dead session is not
        // evidence about this delivery. Same treatment as `Deferred`, different diagnosis.
        assertEquals(0, row.attemptCount)
        assertEquals("no backoff should be armed", null, row.nextAttemptAt)
    }

    @Test
    fun `signed out, no upload is attempted at all`() = runTest {
        dao.seed(queuedReceipt("r1"))
        authRepository.signOut()

        repository(this).processQueue()

        // The pre-flight gate, mirroring the connectivity one. Without it every receipt
        // would spend a request discovering a fact readable locally -- and would wake the
        // radio to find it out, which is where the battery actually goes.
        assertTrue("nothing should have been sent", api.idempotencyKeys.isEmpty())
        assertEquals(ReceiptStatusColumn.QUEUED, requireNotNull(dao.row("r1")).status)
        assertEquals(0, requireNotNull(dao.row("r1")).attemptCount)
    }

    @Test
    fun `signed out, no worker is re-armed`() = runTest {
        dao.seed(queuedReceipt("r1"))
        authRepository.signOut()

        val workRemains = repository(this).processQueue()

        // Reporting "no work" while signed out is deliberate. A worker re-armed here would
        // wake, find no session, and re-arm itself indefinitely -- burning the OS's
        // scheduling budget on work it cannot do. Signing in is what unblocks it, so
        // signing in is the trigger (see FetchCloneApplication.observeSession).
        assertEquals(false, workRemains)
        assertEquals(0, scheduler.scheduleCount)
    }

    @Test
    fun `another user's receipts are neither uploaded nor shown`() = runTest {
        dao.seed(queuedReceipt("mine"))
        dao.seed(queuedReceipt("theirs", userId = TEST_USER_ID + 1))
        dao.seed(queuedReceipt("orphan", userId = ORPHANED_USER_ID))

        val repository = repository(this)
        repository.processQueue()

        // Points are money-adjacent: uploading someone else's receipt under this account's
        // credentials credits their shopping to this user's balance. That gets a WHERE
        // clause, not a convention.
        assertEquals(listOf("mine"), api.idempotencyKeys)

        val visible: List<Receipt> = repository.observeReceipts().first()
        assertEquals(listOf("mine"), visible.map { it.id })
    }

    @Test
    fun `signing out empties the observed list without deleting anything`() = runTest {
        dao.seed(queuedReceipt("r1"))
        val repository = repository(this)

        assertEquals(1, repository.observeReceipts().first().size)

        authRepository.signOut()

        // The list is empty because there is no user to scope it to...
        assertTrue(repository.observeReceipts().first().isEmpty())
        // ...but the row is still on disk. An undelivered receipt is the user's data, and
        // they may well sign back in. Sign-out is not a delete.
        assertEquals(1, dao.allRows().size)
    }

    @Test
    fun `a receipt keeps the owner it was captured with`() = runTest {
        val repository = repository(this)
        val submitted = repository.submitSimulatedScan()
        assertTrue(submitted is SubmitResult.Success)

        val id = (submitted as SubmitResult.Success).receiptId
        assertEquals(TEST_USER_ID, requireNotNull(dao.row(id)).userId)

        // The owner is resolved at CAPTURE, not at upload. A receipt scanned offline by one
        // account and uploaded after a different account signs in must still be credited to
        // whoever actually scanned it -- the same reasoning that snapshots
        // `discountPercentage` into the line items rather than reading the offers table
        // later.
        authRepository.signOut()
        assertEquals(TEST_USER_ID, requireNotNull(dao.row(id)).userId)
    }

    @Test
    fun `scanning with no session is refused rather than misattributed`() = runTest {
        authRepository.signOut()

        val result = repository(this).submitSimulatedScan()

        // Inventing an owner would be worse than not taking the receipt: a row attributed
        // to the wrong account is not recoverable, and a refused scan is.
        assertEquals(SubmitResult.SignedOut, result)
        assertTrue(dao.allRows().isEmpty())
    }

    // -----------------------------------------------------------------------------

    private fun repository(scope: TestScope) = DefaultReceiptRepository(
        receiptDao = dao,
        cartsApi = api,
        authRepository = authRepository,
        scanner = ReceiptScanner(FakeOffersDao(listOf(OFFER))),
        networkMonitor = networkMonitor,
        processor = StubAwardingProcessor,
        scheduler = scheduler,
        clock = clock,
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

    private object StubAwardingProcessor : ReceiptProcessor {
        override suspend fun poll(receipt: Receipt): ReceiptOutcome = ReceiptOutcome.StillProcessing
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val LINE_ITEMS_JSON =
            """[{"productId":1,"title":"Red Lipstick","quantity":2,"unitPriceCents":1299,"discountPercentage":40.0}]"""

        val OFFER = com.fetchclone.core.data.database.entity.OfferEntity(
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
    }
}
