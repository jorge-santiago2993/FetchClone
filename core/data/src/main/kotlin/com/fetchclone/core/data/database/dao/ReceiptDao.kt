package com.fetchclone.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the `receipts` outbox.
 *
 * ## The one idea to take from this file: transitions are conditional writes
 *
 * Every state change here is an `UPDATE ... WHERE id = :id AND status = <expected>` that
 * **returns the number of rows it changed**. That is a compare-and-set, and it is what
 * makes the outbox safe without a global lock.
 *
 * The spec calls for three independent triggers — an application-scoped coroutine on
 * submit, an app-foreground hook, and a WorkManager worker. Nothing stops two of them
 * running at once: the user submits a receipt (trigger 1) at the moment the OS decides
 * connectivity returned (trigger 3). With a plain `UPDATE ... WHERE id = :id`, both
 * passes would read the same `QUEUED` row from `findPending`, both would mark it
 * `UPLOADING`, and both would POST it. Idempotency keys mean the server survives that,
 * but we would still have burned a duplicate request and raced two writers to the same
 * row.
 *
 * With the guard, exactly one of them observes `1` from [markUploading] and proceeds;
 * the other observes `0` and skips the receipt. The claim is atomic because SQLite
 * applies the `WHERE` inside the statement. The repository *also* holds a `Mutex`, but
 * that only serialises passes within one process — the DAO guard is what remains correct
 * when the worker runs in a separate process, which is a real WorkManager configuration.
 *
 * **Alternative considered and declined:** `@Update` on the whole entity. It is less
 * code, but it is a blind last-writer-wins overwrite of every column, so a reconciliation
 * pass writing `AWARDED` could be silently clobbered by an in-flight upload pass writing
 * `PROCESSING` from a stale copy it read moments earlier. Targeted, guarded `@Query`
 * updates make each transition state its own precondition.
 *
 * ## Why there is no `clear()` / `deleteAll()`
 *
 * The scaffolding version of this DAO had one. That is right for a cache and wrong for
 * an outbox: a row here can be the only copy of something the user did. Nothing in the
 * app is allowed to bulk-delete receipts, so the method does not exist to be called by
 * accident. See `ReceiptEntity`.
 */
@Dao
interface ReceiptDao {

    /**
     * Inserts a newly captured receipt. **This call is the commit.**
     *
     * `OnConflictStrategy.ABORT` (Room's default, stated explicitly here because it is
     * load-bearing) rather than `REPLACE`: the id is a client-generated UUID, so a
     * collision is not a legitimate re-submission, it is a bug — a caller reusing an id,
     * or a double-tap that got past the UI. `REPLACE` would silently reset a receipt's
     * attempt count and status, resurrecting a terminal row. Aborting turns that into a
     * loud `SQLiteConstraintException`.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: ReceiptEntity)

    /**
     * Every receipt, newest capture first — the backing stream for the receipts list.
     *
     * A `Flow`, so Room re-emits on every write the processor makes. That is the whole
     * "Room is the single source of truth" payoff for this feature: the UI subscribes
     * once and the state machine animates itself as rows transition, with no event bus,
     * no manual refresh, and no path by which the screen can disagree with the database.
     *
     * Ordered by `capturedAt`, not by status: the list is a chronological history, and
     * a receipt must not jump around as it uploads.
     */
    @Query("SELECT * FROM receipts ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<ReceiptEntity>>

    /** Single-row read, for reconciliation and for tests asserting on a transition. */
    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun findById(id: String): ReceiptEntity?

    /**
     * Receipts the upload loop should attempt right now.
     *
     * Three predicates, each load-bearing:
     * - `status IN (QUEUED, FAILED)` — the two states from which a POST is correct.
     *   `UPLOADING` is excluded because someone is already sending it; `PROCESSING` is
     *   excluded because the server already has it and re-POSTing would be wrong;
     *   `AWARDED` / `REJECTED` are terminal.
     * - `nextAttemptAt IS NULL OR nextAttemptAt <= :now` — the persisted backoff
     *   schedule. A fresh receipt has `NULL` and is eligible immediately; a failed one
     *   waits. Because this lives in the database rather than in a coroutine timer, a
     *   process restart resumes the same schedule instead of retrying everything at once.
     * - `attemptCount < :maxAttempts` — **this is what makes retry exhaustion terminal.**
     *   An exhausted row keeps `status = FAILED` and simply stops being selected. See
     *   `ReceiptStatus.Failed.isExhausted` for why exhaustion is derived from the counter
     *   rather than stored as a separate status.
     *
     * `:now` is a parameter rather than SQLite's own clock so the processor's notion of
     * time comes from one injected source and tests can control it.
     *
     * Returns a `List`, not a `Flow`: this is a one-shot work queue read, and a self-
     * retriggering stream over a table the processor is actively writing to is a loop
     * waiting to happen.
     */
    @Query(
        """
        SELECT * FROM receipts
        WHERE status IN ('${ReceiptStatusColumn.QUEUED}', '${ReceiptStatusColumn.FAILED}')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
          AND attemptCount < :maxAttempts
        ORDER BY capturedAt ASC
        """,
    )
    suspend fun findPending(now: Long, maxAttempts: Int): List<ReceiptEntity>

    /**
     * Receipts the server has accepted but not yet resolved — the reconciliation queue.
     *
     * `serverId IS NOT NULL` is belt-and-braces: reaching `PROCESSING` always stores one,
     * so a `PROCESSING` row without a `serverId` would be a bug. Filtering here means
     * reconciliation cannot be handed a receipt it has no way to look up.
     */
    @Query(
        """
        SELECT * FROM receipts
        WHERE status = '${ReceiptStatusColumn.PROCESSING}' AND serverId IS NOT NULL
        ORDER BY capturedAt ASC
        """,
    )
    suspend fun findProcessing(): List<ReceiptEntity>

    // -----------------------------------------------------------------------------
    // Transitions. Each returns the rows updated: 1 = the claim/transition succeeded,
    // 0 = someone else moved the row first, so the caller must not proceed.
    // -----------------------------------------------------------------------------

    /**
     * Claims a receipt for upload: `QUEUED | FAILED -> UPLOADING`.
     *
     * The guard is the concurrency control for the whole pipeline — see this class's
     * header. Callers **must** check the return value and skip the receipt on `0`.
     */
    @Query(
        """
        UPDATE receipts SET status = '${ReceiptStatusColumn.UPLOADING}'
        WHERE id = :id
          AND status IN ('${ReceiptStatusColumn.QUEUED}', '${ReceiptStatusColumn.FAILED}')
        """,
    )
    suspend fun markUploading(id: String): Int

    /**
     * Upload accepted: `UPLOADING -> PROCESSING`, recording the server's id.
     *
     * `nextAttemptAt` is cleared because the backoff schedule belonged to the *upload*
     * loop, and this receipt has left it. Leaving a stale timestamp behind would be
     * harmless today but is exactly the kind of dead field that misleads the next reader.
     */
    @Query(
        """
        UPDATE receipts
        SET status = '${ReceiptStatusColumn.PROCESSING}', serverId = :serverId, nextAttemptAt = NULL
        WHERE id = :id AND status = '${ReceiptStatusColumn.UPLOADING}'
        """,
    )
    suspend fun markProcessing(id: String, serverId: String): Int

    /**
     * Terminal success: `PROCESSING -> AWARDED`.
     *
     * Guarded on `PROCESSING` so a late or duplicated poll response cannot re-award a
     * receipt that has already resolved. The second write returns `0` and is dropped.
     */
    @Query(
        """
        UPDATE receipts SET status = '${ReceiptStatusColumn.AWARDED}', awardedPoints = :points
        WHERE id = :id AND status = '${ReceiptStatusColumn.PROCESSING}'
        """,
    )
    suspend fun markAwarded(id: String, points: Int): Int

    /**
     * Terminal rejection. Reachable from two different places, which is why the guard
     * lists two states:
     * - `UPLOADING` — the POST itself came back 4xx.
     * - `PROCESSING` — the server accepted the upload and later decided against it.
     *
     * **No `nextAttemptAt` is written, and `attemptCount` is untouched.** That is the
     * mechanical expression of "4xx is terminal": nothing schedules a retry, and
     * `findPending` cannot select a `REJECTED` row anyway. The unit test the spec asks
     * for asserts exactly this.
     */
    @Query(
        """
        UPDATE receipts SET status = '${ReceiptStatusColumn.REJECTED}', rejectReason = :reason
        WHERE id = :id
          AND status IN ('${ReceiptStatusColumn.UPLOADING}', '${ReceiptStatusColumn.PROCESSING}')
        """,
    )
    suspend fun markRejected(id: String, reason: String): Int

    /**
     * Retryable failure: `UPLOADING -> FAILED`, bumping the attempt counter and arming
     * the persisted backoff.
     *
     * `attemptCount = attemptCount + 1` is computed **in SQL, not in Kotlin**. Reading
     * the row, incrementing in memory and writing it back is a lost-update race: two
     * passes both read `2` and both write `3`, and the receipt gets a free extra attempt.
     * Letting SQLite do the arithmetic under the row lock makes the increment atomic.
     *
     * When the incremented count reaches the budget the row simply stops matching
     * `findPending`, which is how exhaustion becomes terminal without a second status.
     */
    @Query(
        """
        UPDATE receipts
        SET status = '${ReceiptStatusColumn.FAILED}',
            attemptCount = attemptCount + 1,
            nextAttemptAt = :nextAttemptAt
        WHERE id = :id AND status = '${ReceiptStatusColumn.UPLOADING}'
        """,
    )
    suspend fun markFailed(id: String, nextAttemptAt: Long): Int

    /**
     * Crash recovery: `UPLOADING -> QUEUED` for every row stranded mid-flight.
     *
     * A receipt is `UPLOADING` only while a POST is actually in flight, and that state
     * lives in the database, so if the process dies there the row stays `UPLOADING`
     * forever — invisible to [findPending], never retried, silently lost. The spec's
     * "the processor must be resumable after process death" requires sweeping them back.
     *
     * Doing this unconditionally at processor start is safe because a surviving in-process
     * upload is already serialised behind the repository's `Mutex`, so no live request
     * can be reset out from under itself.
     *
     * Re-sending is safe **only** because of the `Idempotency-Key`: we cannot tell whether
     * the dead request reached the server, and idempotency makes that distinction
     * unnecessary. This method is the clearest single answer to "why does the primary key
     * double as the idempotency key" — without it, this recovery would risk double awards.
     *
     * The attempt counter is deliberately **not** incremented: a process death is not the
     * receipt's fault, and charging it an attempt would let a crash loop exhaust the
     * user's retry budget for a receipt the server may never have seen.
     */
    @Query(
        """
        UPDATE receipts SET status = '${ReceiptStatusColumn.QUEUED}'
        WHERE status = '${ReceiptStatusColumn.UPLOADING}'
        """,
    )
    suspend fun requeueStalledUploads(): Int

    /**
     * Releases one receipt's upload claim: `UPLOADING -> QUEUED`, for a single id.
     *
     * The targeted counterpart to [requeueStalledUploads], and it exists for **coroutine
     * cancellation**, which is a different failure from process death and needs a
     * different remedy.
     *
     * When the processor's scope is cancelled mid-upload, the coroutine unwinds through
     * `CancellationException` — which must not be swallowed — leaving the row claimed as
     * `UPLOADING` with nobody uploading it. [requeueStalledUploads] would eventually
     * recover it, but only on the next process start, so the receipt would sit invisible
     * to [findPending] for the rest of the session. Releasing just this row on the way out
     * keeps the very next pass able to pick it up.
     *
     * The caller must do this inside `withContext(NonCancellable)`, because by that point
     * the coroutine is already cancelled and an ordinary suspending write would itself
     * throw. See `DefaultReceiptRepository.uploadOne`.
     *
     * `attemptCount` is deliberately not incremented: a cancellation is our lifecycle
     * event, not a failed delivery, and charging the receipt for it would let a few
     * screen rotations burn a user's retry budget.
     */
    @Query(
        """
        UPDATE receipts SET status = '${ReceiptStatusColumn.QUEUED}'
        WHERE id = :id AND status = '${ReceiptStatusColumn.UPLOADING}'
        """,
    )
    suspend fun releaseUploadClaim(id: String): Int

    /**
     * How many receipts still need uploading — including those currently waiting out a
     * backoff delay.
     *
     * Drives `ReceiptUploadWorker`'s choice between `Result.success()` and
     * `Result.retry()`. The `nextAttemptAt` filter from [findPending] is deliberately
     * **absent**: a receipt waiting for its backoff window is unfinished work, and the
     * worker must be rescheduled for it even though this pass could not send it. Reusing
     * [findPending]'s predicate here would report "nothing left to do" the moment every
     * remaining receipt was mid-backoff, and the outbox would stall until some other
     * trigger happened to fire.
     *
     * `PROCESSING` receipts are **not** counted, and that is the other half of the same
     * decision. They are resolved by reconciliation, which the worker does not run — so
     * counting them would make the worker reschedule itself forever over work it cannot
     * perform.
     */
    @Query(
        """
        SELECT COUNT(*) FROM receipts
        WHERE status IN ('${ReceiptStatusColumn.QUEUED}', '${ReceiptStatusColumn.FAILED}')
          AND attemptCount < :maxAttempts
        """,
    )
    suspend fun countPendingUploads(maxAttempts: Int): Int
}
