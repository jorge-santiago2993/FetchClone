package com.fetchclone.core.data.repository

import com.fetchclone.core.data.database.dao.ReceiptDao
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.di.ApplicationScope
import com.fetchclone.core.data.di.IoDispatcher
import com.fetchclone.core.data.mapper.ReceiptLineItemsCodec
import com.fetchclone.core.data.mapper.newReceiptEntity
import com.fetchclone.core.data.mapper.toDomain
import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.network.CartsApi
import com.fetchclone.core.data.network.NetworkMonitor
import com.fetchclone.core.data.network.model.CartProductRequest
import com.fetchclone.core.data.network.model.CartRequest
import com.fetchclone.core.data.receipt.ReceiptOutcome
import com.fetchclone.core.data.receipt.ReceiptProcessor
import com.fetchclone.core.data.receipt.ReceiptScanner
import com.fetchclone.core.data.receipt.RetryBackoff
import com.fetchclone.core.data.receipt.UploadFailure
import com.fetchclone.core.data.receipt.classifyUploadFailure
import com.fetchclone.core.data.work.OutboxSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The receipt outbox: durable local queue, upload loop, retry policy, reconciliation.
 *
 * ## Why `@Singleton`
 *
 * Two reasons, and the second is the load-bearing one.
 *
 * It owns [uploadMutex] and [hasRecoveredStalledUploads], which are **process-wide
 * invariants**. A second instance would have its own mutex, so two upload passes could run
 * concurrently believing they were serialised — the DAO's compare-and-set guards would
 * still keep that correct, but only by design, not by luck, and the crash-recovery sweep
 * would run more than once per process.
 *
 * It is also injected into `ReceiptUploadWorker`, which WorkManager instantiates on its own
 * schedule. Singleton scoping means the worker shares this instance's coordination rather
 * than constructing a parallel universe of it.
 *
 * ## The concurrency model in one place
 *
 * Three independent triggers can call [processQueue] at any time (submit, foreground,
 * WorkManager), so "two passes at once" is the normal case, not an edge case. Three
 * mechanisms keep that correct, at different scopes:
 *
 * | Mechanism | Protects against | Scope |
 * |---|---|---|
 * | [uploadMutex] | two passes interleaving in this process | one process |
 * | `ReceiptDao.markUploading`'s CAS | two claimants of one receipt | the database |
 * | The `Idempotency-Key` header | the server seeing one receipt twice | the backend |
 *
 * They are **layered, not redundant**. The mutex is an optimisation — it stops wasted work
 * before it starts. The DAO guard is the actual correctness boundary, and it is the one
 * that still holds if WorkManager runs in a separate process, where a `Mutex` is just an
 * object in someone else's heap. The idempotency key is the last line, covering the case
 * neither of the first two can: a request that reached the server but whose response we
 * never saw.
 *
 * [reconcileMutex] is separate from [uploadMutex] on purpose. The two loops touch disjoint
 * row sets — uploads select `QUEUED`/`FAILED`, reconciliation selects `PROCESSING` — so
 * serialising them against each other would buy nothing and would let a slow poll block an
 * upload that connectivity had just made possible.
 *
 * ## Threading
 *
 * Every public method wraps its body in `withContext(ioDispatcher)`. `OffersRemoteMediator`
 * documents at length why it does *not* do this, and both are right, because they are
 * different shapes of work. That class awaits individual Room and Retrofit calls, each
 * already main-safe, and adds nothing of its own. This class runs a **loop with its own
 * control flow** — read a batch, iterate, branch, write — which is work in its own right
 * and needs a stated home rather than inheriting whatever context a caller happened to be
 * on. Injecting the dispatcher (rather than naming `Dispatchers.IO`) is what lets tests
 * swap in a test dispatcher and get determinism.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultReceiptRepository @Inject constructor(
    private val receiptDao: ReceiptDao,
    private val cartsApi: CartsApi,
    private val authRepository: AuthRepository,
    private val scanner: ReceiptScanner,
    private val networkMonitor: NetworkMonitor,
    private val processor: ReceiptProcessor,
    private val scheduler: OutboxSyncScheduler,
    private val clock: Clock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ReceiptRepository {

    private val uploadMutex = Mutex()
    private val reconcileMutex = Mutex()

    /**
     * Whether this process has already swept up uploads stranded by a previous process's
     * death.
     *
     * Only ever touched inside [uploadMutex], so a plain `var` is sufficient — no
     * `@Volatile`, no atomic. The mutex already establishes the happens-before edge.
     *
     * **Why once per process rather than once per pass.** `requeueStalledUploads()` resets
     * *every* `UPLOADING` row, so running it on each pass would reset a claim held by a
     * genuinely in-flight upload in another process. Crash recovery is a process-start
     * concern, and this flag says so precisely. Cancellation — the other way a claim can be
     * abandoned — is handled per-receipt by [releaseClaimUninterruptibly], so nothing
     * depends on this sweep running more than once.
     */
    private var hasRecoveredStalledUploads = false

    override suspend fun submitSimulatedScan(): SubmitResult = withContext(ioDispatcher) {
        // The owner is resolved HERE, at capture, not at upload. Same reasoning as
        // snapshotting `discountPercentage` into the line items: a receipt records the
        // world as it was when the user acted. Reading the user at upload time would
        // attribute a receipt scanned offline by one account to whoever happened to be
        // signed in when connectivity returned -- and points are money-adjacent.
        val user = authRepository.currentUser() ?: return@withContext SubmitResult.SignedOut

        val lineItems = scanner.scan()
        if (lineItems.isEmpty()) return@withContext SubmitResult.NoOffersCached

        val receiptId = UUID.randomUUID().toString()

        // ── THE COMMIT ────────────────────────────────────────────────────────────────
        // One local insert. No network call on this path, and nothing below is allowed to
        // fail the submission. Once this returns, the receipt is durable and the user's
        // part is done.
        receiptDao.insert(
            newReceiptEntity(
                id = receiptId,
                userId = user.id,
                capturedAt = clock.millis(),
                lineItems = lineItems,
            ),
        )

        // ── TRIGGERS, strictly after the commit ───────────────────────────────────────
        // Ordering is the point: if the process died between these two statements the
        // receipt would still be on disk and would be picked up by the next foreground or
        // by the worker. Kicking off delivery before the insert would create a window
        // where an upload runs for a receipt that is not durably recorded.
        //
        // 1. Application scope: starts the upload in milliseconds, while the user is still
        //    looking at the screen. `launch` and not `await` — submit must not wait for
        //    the network, and this scope outlives the ViewModel that called us so a
        //    navigation away cannot cancel the upload.
        applicationScope.launch { processQueue() }

        // 2. WorkManager: the durable fallback. Enqueued even though (1) has probably
        //    already succeeded, because (1) evaporates if the process is killed mid-upload
        //    and the OS-owned job does not. See `OutboxSyncScheduler`.
        scheduler.scheduleUpload()

        SubmitResult.Success(receiptId)
    }

    /**
     * The signed-in user's receipts, re-subscribing whenever the session changes.
     *
     * `flatMapLatest` over [AuthRepository.authState] rather than a one-shot read of the
     * current user: the receipts screen can be on screen when a session expires, and a
     * plain `observeForUser(idReadOnce)` would keep showing the previous account's
     * receipts until something happened to recreate the ViewModel. Signing out emits an
     * empty list, and signing in as someone else swaps the query to their rows.
     */
    override fun observeReceipts(): Flow<List<Receipt>> =
        authRepository.authState
            .map { state -> (state as? AuthState.Authenticated)?.user?.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                if (userId == null) {
                    flowOf(emptyList())
                } else {
                    // Mapped at the edge so entities never leave :core:data. Not wrapped in
                    // `flowOn(ioDispatcher)`: Room already emits on its own executor, and
                    // the mapping is a cheap in-memory transform of a page-sized list.
                    receiptDao.observeForUser(userId)
                        .map { entities -> entities.map(ReceiptEntity::toDomain) }
                }
            }

    override suspend fun processQueue(): Boolean = withContext(ioDispatcher) {
        uploadMutex.withLock {
            if (!hasRecoveredStalledUploads) {
                // Receipts left `UPLOADING` by a process that died mid-request. Re-sending
                // is safe only because of the idempotency key — see
                // `ReceiptDao.requeueStalledUploads`.
                receiptDao.requeueStalledUploads()
                hasRecoveredStalledUploads = true
            }

            // ── PRE-FLIGHT: do not attempt without a network ──────────────────────────
            // Skipping the whole pass, rather than letting each upload fail its way to the
            // same conclusion, is the point. Every receipt would otherwise spend an attempt
            // discovering a fact we could have asked the OS once — and it would wake the
            // radio to find out, which is where the battery actually goes.
            //
            // The rows are left completely untouched: still QUEUED, counter unmoved, no
            // backoff armed. The badge therefore reads "Queued", which is the honest word
            // for a receipt waiting on connectivity rather than on a server.
            //
            // See NetworkMonitor for the reproduction this prevents.
            if (!networkMonitor.isOnline()) {
                return@withLock reportWorkRemaining(userId = authRepository.currentUser()?.id)
            }

            // ── PRE-FLIGHT: do not attempt without a session ──────────────────────────
            // The exact shape of the connectivity gate above, for the exact same reason.
            // Without a valid session every upload would 401, and while
            // `UploadFailure.Unauthenticated` handles that correctly, discovering it once
            // per receipt spends a request each to learn something we can read locally.
            //
            // The symmetry is the story worth telling: the outbox refuses to charge a
            // receipt for a problem that is not the receipt's. No radio, no session --
            // neither is the receipt's fault, and neither costs it an attempt.
            val user = authRepository.currentUser() ?: return@withLock reportWorkRemaining(userId = null)

            // `now` is read once, so every receipt in this pass is judged against the same
            // instant. Re-reading the clock per receipt would let a slow pass start
            // treating receipts as due mid-loop, making the batch non-deterministic and
            // the tests unreproducible.
            val now = clock.millis()
            val pending = receiptDao.findPending(user.id, now, ReceiptStatus.MAX_UPLOAD_ATTEMPTS)

            for (entity in pending) {
                uploadOne(entity)
            }

            reportWorkRemaining(userId = user.id)
        }
    }

    /**
     * Reports whether receipts still need uploading, **and re-arms the durable worker if so**.
     *
     * The re-arming is the load-bearing half. `scheduleUpload()` used to be called only from
     * [submitSimulatedScan], which left a gap: a pass that failed a receipt — or skipped one
     * because there was no network — did not guarantee a pending job. If WorkManager's
     * previous job had already completed with `Result.success()`, nothing was left to retry
     * the receipt except the user happening to reopen the app.
     *
     * Enqueueing here closes that. It is cheap and idempotent: `ExistingWorkPolicy.KEEP`
     * means a job already pending is left alone rather than cancelled and rebuilt, so
     * calling this on every pass cannot reset a backoff or displace a job that is about to
     * run.
     */
    /**
     * @param userId the signed-in account, or null when there is no session. With no
     *   session there is no work *this app can do*, so nothing is rescheduled — a worker
     *   re-armed while signed out would wake, find no session, and re-arm itself forever.
     *   Signing back in runs a fresh pass, which re-arms it if anything is still pending.
     */
    private suspend fun reportWorkRemaining(userId: Int?): Boolean {
        if (userId == null) return false
        val workRemains = receiptDao.countPendingUploads(userId, ReceiptStatus.MAX_UPLOAD_ATTEMPTS) > 0
        if (workRemains) scheduler.scheduleUpload()
        return workRemains
    }

    /**
     * One receipt's trip through the upload half of the state machine.
     *
     * ```
     *  claim (CAS)  ->  POST  ->  2xx      -> PROCESSING
     *                             4xx      -> REJECTED   (terminal, no retry scheduled)
     *                             5xx / IO -> FAILED      (attempt++, backoff armed)
     * ```
     */
    private suspend fun uploadOne(entity: ReceiptEntity) {
        // Claim it. A `0` means another pass — or another process — got there first, so
        // this one must not send. Everything below runs only for the winner.
        if (receiptDao.markUploading(entity.id) == 0) return

        try {
            val response = cartsApi.addCart(
                // The receipt's own primary key, unchanged on every attempt. This is what
                // makes retrying and crash recovery safe. Never generate a fresh key here.
                idempotencyKey = entity.id,
                body = entity.toCartRequest(),
            )
            // DummyJSON's cart id is an Int; the column is a String because a real backend
            // would return an opaque identifier. Converting at the edge means the outbox
            // never assumes server ids are numeric.
            receiptDao.markProcessing(entity.id, serverId = response.id.toString())
        } catch (cancellation: CancellationException) {
            // ── DO NOT SWALLOW ────────────────────────────────────────────────────────
            // Catching this to "handle the error" is the classic structured-concurrency
            // bug: the coroutine keeps running after being cancelled, `withContext`
            // stops honouring its parent, and shutdown quietly hangs.
            //
            // But rethrowing immediately would strand the row as `UPLOADING` with nobody
            // uploading it, invisible to `findPending` for the rest of the process. So
            // the claim is released first — necessarily inside `NonCancellable`, because
            // this coroutine is already cancelled and an ordinary suspending write would
            // itself throw before touching the database.
            //
            // Release, then rethrow, unchanged. Both halves matter.
            releaseClaimUninterruptibly(entity.id)
            throw cancellation
        } catch (error: Throwable) {
            // Connectivity is re-read here, after the failure, not reused from the
            // pre-flight check above. The window between them is exactly when a train
            // enters a tunnel, and a receipt must not be charged an attempt for a
            // connection that disappeared underneath it.
            when (val failure = classifyUploadFailure(error, isOnline = networkMonitor.isOnline())) {
                // 4xx: the server refused. Terminal. Note what is NOT happening here —
                // no `attemptCount` bump, no `nextAttemptAt`, nothing that could put this
                // receipt back in front of `findPending`. "4xx is terminal" is enforced by
                // the absence of a retry, not by a flag someone has to remember to check.
                is UploadFailure.Rejected ->
                    receiptDao.markRejected(entity.id, reason = failure.reason.name)

                // 5xx / IO: unknown outcome, so try again later.
                UploadFailure.Retryable -> {
                    // Mirrors the `attemptCount = attemptCount + 1` that `markFailed`
                    // performs in SQL. The two cannot disagree: `markFailed` is guarded on
                    // `status = 'UPLOADING'`, and this pass holds that claim, so no other
                    // writer can have incremented the counter since we read it.
                    val attempts = entity.attemptCount + 1
                    receiptDao.markFailed(
                        id = entity.id,
                        // Absolute wall-clock deadline, stored in the row. Not a `delay()`
                        // — a coroutine timer dies with the process, and the whole premise
                        // of the outbox is surviving that.
                        nextAttemptAt = clock.millis() + RetryBackoff.delayMillis(attempts),
                    )
                }

                // Connectivity vanished mid-request: we never really asked, so this costs
                // the receipt nothing. Release the claim and leave it QUEUED, exactly as it
                // was before this pass touched it — no attempt counted, no backoff armed.
                //
                // `releaseUploadClaim` rather than `markFailed` is the whole difference:
                // it restores the row instead of recording a failure against it. The
                // constrained worker takes it from here.
                UploadFailure.Deferred -> receiptDao.releaseUploadClaim(entity.id)

                // The session died between the pre-flight check and this response. Same
                // treatment as Deferred and for the same reason: the receipt is not at
                // fault, so it keeps its full attempt budget and simply waits -- here, for
                // the user to sign in again rather than for a radio.
                //
                // Note what this prevents. Without the branch, a 401 is a 4xx, and a 4xx
                // is REJECTED -- the app would tell the user their receipt was refused
                // because their token expired. See UploadFailure.Unauthenticated.
                UploadFailure.Unauthenticated -> receiptDao.releaseUploadClaim(entity.id)
            }
        }
    }

    override suspend fun reconcileProcessing(): Unit = withContext(ioDispatcher) {
        reconcileMutex.withLock {
            // No session, nothing to reconcile: polling is an authenticated call, and the
            // receipts of a signed-out user are not this session's business.
            val user = authRepository.currentUser() ?: return@withLock

            for (entity in receiptDao.findProcessing(user.id)) {
                try {
                    when (val outcome = processor.poll(entity.toDomain())) {
                        // No decision yet. Leave the row exactly as it is; the next
                        // foreground asks again. There is deliberately no bookkeeping for
                        // "how many times have we polled" — see `RemoteReceiptProcessor`
                        // for why a real implementation would need to bound it.
                        ReceiptOutcome.StillProcessing -> Unit

                        is ReceiptOutcome.Awarded ->
                            receiptDao.markAwarded(entity.id, points = outcome.points)

                        is ReceiptOutcome.Rejected ->
                            receiptDao.markRejected(entity.id, reason = outcome.reason.name)
                    }
                } catch (cancellation: CancellationException) {
                    // Same rule as the upload loop. No claim to release here — polling
                    // does not transition the row before it has an answer, so an
                    // interrupted poll leaves nothing behind.
                    throw cancellation
                } catch (_: Throwable) {
                    // One receipt failing to poll must not abandon the rest of the pass:
                    // a single receipt the server has genuinely lost would otherwise stall
                    // reconciliation for every receipt behind it in the list.
                    //
                    // Swallowing is safe *specifically* because the failure is idempotent
                    // and self-correcting: the row stays `PROCESSING`, which is exactly
                    // where a failed poll should leave it, and the next foreground retries
                    // it. Nothing is lost and no state is half-written. That is a very
                    // different situation from swallowing a failed *upload*, which is why
                    // this loop can absorb an error and `uploadOne` cannot.
                }
            }
        }
    }

    /**
     * Releases an upload claim while the surrounding coroutine is already cancelled.
     *
     * `NonCancellable` is the documented idiom for exactly this: cleanup that must complete
     * during unwinding. Without it every suspension point below would immediately throw
     * `CancellationException` and the write would never reach the database.
     *
     * It is a genuinely narrow tool and easy to misuse — code inside it cannot be cancelled
     * at all, so an unbounded operation here would hang shutdown. One guarded single-row
     * `UPDATE` is the right size for it.
     */
    private suspend fun releaseClaimUninterruptibly(receiptId: String) {
        withContext(NonCancellable) {
            receiptDao.releaseUploadClaim(receiptId)
        }
    }
}

/**
 * Builds the upload body from a stored receipt.
 *
 * Only product ids and quantities are sent. The prices on our line items are local — for
 * display and for the award — and are deliberately not asserted to the backend, which
 * knows what things cost. A client that told the server what a purchase was worth would be
 * a client that could be modified to say it was worth more.
 */
private fun ReceiptEntity.toCartRequest(): CartRequest =
    CartRequest(
        // The receipt's own stored owner, stamped at capture -- not a lookup of who is
        // signed in right now. A receipt scanned offline by one account and uploaded after
        // a different account signs in must still be credited to the person who scanned it.
        //
        // Passed explicitly, never defaulted: kotlinx.serialization omits default values
        // from the encoded JSON, so a Kotlin default here would silently drop the field
        // the server requires and every upload would 400. See CartRequest.
        userId = userId,
        products = ReceiptLineItemsCodec.decode(lineItemsJson).map { item ->
            CartProductRequest(id = item.productId, quantity = item.quantity)
        },
    )
