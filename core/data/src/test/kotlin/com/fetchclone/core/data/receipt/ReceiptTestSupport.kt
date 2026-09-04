package com.fetchclone.core.data.receipt

import androidx.paging.PagingSource
import com.fetchclone.core.data.database.dao.OffersDao
import com.fetchclone.core.data.database.dao.ReceiptDao
import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.AuthUser
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.core.data.network.CartsApi
import com.fetchclone.core.data.network.NetworkMonitor
import com.fetchclone.core.data.network.model.CartRequest
import com.fetchclone.core.data.network.model.CartResponse
import com.fetchclone.core.data.repository.AuthRepository
import com.fetchclone.core.data.repository.LoginOutcome
import com.fetchclone.core.data.work.OutboxSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Hand-written fakes for the receipt pipeline's unit tests.
 *
 * ## Why fakes rather than a mocking framework
 *
 * The behaviour under test is **stateful**: a receipt's status transitions, and the next
 * assertion depends on the previous write having landed. Mocks express "this method was
 * called with these arguments", which is the wrong shape — a test that stubs
 * `markUploading` to return 1 has asserted nothing about whether the guard would actually
 * have allowed it. [FakeReceiptDao] keeps real rows in a map and applies the real
 * preconditions, so a test can assert on the *resulting state* rather than on a call log.
 *
 * ## The honest caveat about [FakeReceiptDao]
 *
 * It reimplements `ReceiptDao`'s SQL in Kotlin, so it can drift from the real queries — a
 * fake that silently disagrees with production SQL is worse than no fake at all. Two things
 * limit that risk:
 *
 * 1. Each method below states the `WHERE` clause it mirrors, so a reviewer can diff the two
 *    by eye.
 * 2. `ReceiptDaoTest` (androidTest) exercises the **real** queries against an in-memory
 *    Room database, covering exactly the guard semantics this fake asserts.
 *
 * The division of labour is deliberate: the instrumented test proves the SQL is right, and
 * these JVM tests — which run in milliseconds and can control the clock — prove the
 * repository's *logic* is right. Running the repository tests on a device instead would be
 * slower and would still need a controllable clock.
 */

/**
 * A [Clock] whose time the test moves by hand.
 *
 * `Clock.fixed()` from the JDK covers a single instant; the outbox needs time to *advance*
 * — arm a backoff, jump past it, assert the receipt became eligible. Hence a mutable
 * subclass rather than the built-in factory.
 */
internal class MutableTestClock(
    var nowMillis: Long = 1_000_000L,
) : Clock() {
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advanceBy(millis: Long) { nowMillis += millis }
}

/** The account every fixture in these tests belongs to. */
internal const val TEST_USER_ID = 7

/**
 * A signed-in session that tests can end.
 *
 * Only the two members the outbox actually uses are meaningful; the rest exist to satisfy
 * the interface. [signOut] is what makes the auth-gate tests possible -- it is the closest
 * a JVM test gets to "the user's refresh token expired mid-pass".
 */
internal class FakeAuthRepository(
    user: AuthUser? = AuthUser(
        id = TEST_USER_ID,
        username = "emilys",
        firstName = "Emily",
        lastName = "Johnson",
        email = "emily@example.com",
        avatarUrl = "",
    ),
) : AuthRepository {

    private val state = MutableStateFlow<AuthState>(
        if (user == null) AuthState.SignedOut(SignOutReason.NEVER_SIGNED_IN)
        else AuthState.Authenticated(user),
    )

    override val authState: StateFlow<AuthState> = state

    override fun currentUser(): AuthUser? = (state.value as? AuthState.Authenticated)?.user

    override suspend fun restoreSession() = Unit

    override suspend fun login(username: String, password: String) = LoginOutcome.Success

    override suspend fun logout() = signOut()

    fun signOut() {
        state.value = AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)
    }
}

/**
 * In-memory [ReceiptDao] that mirrors the real DAO's guarded updates.
 *
 * Every transition below returns the rows it changed, exactly as the SQL does, so a caller
 * that ignores the return value fails here the same way it would in production.
 */
internal class FakeReceiptDao : ReceiptDao {

    private val rows = MutableStateFlow<Map<String, ReceiptEntity>>(emptyMap())

    /** Test-only accessor; production code only ever sees the interface. */
    fun row(id: String): ReceiptEntity? = rows.value[id]

    fun allRows(): List<ReceiptEntity> = rows.value.values.toList()

    fun seed(vararg entities: ReceiptEntity) {
        rows.value = rows.value + entities.associateBy { it.id }
    }

    override suspend fun insert(receipt: ReceiptEntity) {
        // Mirrors OnConflictStrategy.ABORT: a duplicate id is a bug, not an upsert.
        check(receipt.id !in rows.value) { "duplicate receipt id ${receipt.id}" }
        rows.value = rows.value + (receipt.id to receipt)
    }

    /** `WHERE userId = :userId ORDER BY capturedAt DESC` */
    override fun observeForUser(userId: Int): Flow<List<ReceiptEntity>> =
        rows.map { all ->
            all.values
                .filter { it.userId == userId }
                .sortedByDescending(ReceiptEntity::capturedAt)
        }

    override suspend fun findById(id: String): ReceiptEntity? = rows.value[id]

    /**
     * Mirrors: `userId = :userId AND status IN (QUEUED, FAILED) AND (nextAttemptAt IS NULL
     * OR nextAttemptAt <= :now) AND attemptCount < :maxAttempts ORDER BY capturedAt ASC`
     */
    override suspend fun findPending(userId: Int, now: Long, maxAttempts: Int): List<ReceiptEntity> =
        rows.value.values
            .filter { it.userId == userId }
            .filter { it.status == ReceiptStatusColumn.QUEUED || it.status == ReceiptStatusColumn.FAILED }
            .filter { it.nextAttemptAt == null || it.nextAttemptAt!! <= now }
            .filter { it.attemptCount < maxAttempts }
            .sortedBy(ReceiptEntity::capturedAt)

    /** Mirrors: `userId = :userId AND status = PROCESSING AND serverId IS NOT NULL` */
    override suspend fun findProcessing(userId: Int): List<ReceiptEntity> =
        rows.value.values
            .filter { it.userId == userId }
            .filter { it.status == ReceiptStatusColumn.PROCESSING && it.serverId != null }
            .sortedBy(ReceiptEntity::capturedAt)

    /** Mirrors: `WHERE id = :id AND status IN (QUEUED, FAILED)` */
    override suspend fun markUploading(id: String): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.QUEUED, ReceiptStatusColumn.FAILED),
    ) { it.copy(status = ReceiptStatusColumn.UPLOADING) }

    /** Mirrors: `WHERE id = :id AND status = UPLOADING`, also clearing `nextAttemptAt`. */
    override suspend fun markProcessing(id: String, serverId: String): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.UPLOADING),
    ) {
        it.copy(
            status = ReceiptStatusColumn.PROCESSING,
            serverId = serverId,
            nextAttemptAt = null,
        )
    }

    /** Mirrors: `WHERE id = :id AND status = PROCESSING` */
    override suspend fun markAwarded(id: String, points: Int): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.PROCESSING),
    ) { it.copy(status = ReceiptStatusColumn.AWARDED, awardedPoints = points) }

    /** Mirrors: `WHERE id = :id AND status IN (UPLOADING, PROCESSING)` */
    override suspend fun markRejected(id: String, reason: String): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.UPLOADING, ReceiptStatusColumn.PROCESSING),
    ) { it.copy(status = ReceiptStatusColumn.REJECTED, rejectReason = reason) }

    /**
     * Mirrors: `WHERE id = :id AND status = UPLOADING`, with `attemptCount = attemptCount
     * + 1` computed here the way SQLite computes it there.
     */
    override suspend fun markFailed(id: String, nextAttemptAt: Long): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.UPLOADING),
    ) {
        it.copy(
            status = ReceiptStatusColumn.FAILED,
            attemptCount = it.attemptCount + 1,
            nextAttemptAt = nextAttemptAt,
        )
    }

    /** Mirrors: `WHERE status = UPLOADING` (all rows). */
    override suspend fun requeueStalledUploads(): Int {
        val stalled = rows.value.values.filter { it.status == ReceiptStatusColumn.UPLOADING }
        rows.value = rows.value + stalled.associate {
            it.id to it.copy(status = ReceiptStatusColumn.QUEUED)
        }
        return stalled.size
    }

    /** Mirrors: `WHERE id = :id AND status = UPLOADING` */
    override suspend fun releaseUploadClaim(id: String): Int = updateGuarded(
        id = id,
        allowedFrom = setOf(ReceiptStatusColumn.UPLOADING),
    ) { it.copy(status = ReceiptStatusColumn.QUEUED) }

    /**
     * Mirrors: `userId = :userId AND status IN (QUEUED, FAILED) AND attemptCount <
     * :maxAttempts` — deliberately with **no** `nextAttemptAt` filter, matching the real
     * query.
     */
    override suspend fun countPendingUploads(userId: Int, maxAttempts: Int): Int =
        rows.value.values.count {
            it.userId == userId &&
                (it.status == ReceiptStatusColumn.QUEUED || it.status == ReceiptStatusColumn.FAILED) &&
                it.attemptCount < maxAttempts
        }

    /** The compare-and-set every transition above is built from. */
    private fun updateGuarded(
        id: String,
        allowedFrom: Set<String>,
        transform: (ReceiptEntity) -> ReceiptEntity,
    ): Int {
        val current = rows.value[id] ?: return 0
        if (current.status !in allowedFrom) return 0
        rows.value = rows.value + (id to transform(current))
        return 1
    }
}

/**
 * Scriptable [CartsApi].
 *
 * `addCartResponder` is a lambda rather than a canned value so a test can change the
 * outcome between calls — which is exactly what the retry tests need: fail, fail, then
 * succeed.
 */
internal class FakeCartsApi : CartsApi {

    var addCartResponder: (String, CartRequest) -> CartResponse = { _, _ -> CartResponse(id = 51) }
    var getCartResponder: (String) -> CartResponse = { CartResponse(id = 51) }

    /** Every idempotency key seen, in order — the retry tests assert this never varies. */
    val idempotencyKeys = mutableListOf<String>()

    override suspend fun addCart(idempotencyKey: String, body: CartRequest): CartResponse {
        idempotencyKeys += idempotencyKey
        return addCartResponder(idempotencyKey, body)
    }

    override suspend fun getCart(cartId: String): CartResponse = getCartResponder(cartId)
}

/**
 * [NetworkMonitor] whose answer a test flips with a boolean.
 *
 * The seam is what makes the offline paths testable at all on the JVM: the real
 * implementation needs a `Context` and a live `ConnectivityManager`, so without it the
 * airplane-mode behaviour could only be checked by hand on a device — which is exactly how
 * the bug it guards against went unnoticed in the first place.
 */
internal class FakeNetworkMonitor(var online: Boolean = true) : NetworkMonitor {
    override fun isOnline(): Boolean = online
}

/** Records enqueues without touching WorkManager. See `OutboxSyncScheduler` for why. */
internal class RecordingOutboxSyncScheduler : OutboxSyncScheduler {
    var scheduleCount = 0
        private set

    override fun scheduleUpload() {
        scheduleCount++
    }
}

/** [OffersDao] stub that only answers the one query `ReceiptScanner` uses. */
internal class FakeOffersDao(private val offers: List<OfferEntity> = emptyList()) : OffersDao {

    override suspend fun randomOffers(limit: Int): List<OfferEntity> = offers.take(limit)

    // Not exercised by the receipt pipeline. Failing loudly rather than returning an empty
    // default, so a future test that reaches one of these finds out instead of silently
    // asserting against a fabricated value.
    override suspend fun upsertOffers(offers: List<OfferEntity>) = error("not used")
    override fun pagingSource(): PagingSource<Int, OfferEntity> = error("not used")
    override fun observeCount(): Flow<Int> = error("not used")
    override suspend fun clearAll() = error("not used")
}

/**
 * Builds a real [HttpException] for a status code.
 *
 * Constructed from an actual Retrofit `Response` rather than mocked, so the tests exercise
 * the same `e.code()` path production does — the classifier's whole job is reading that
 * code, and a stub would let it be wrong.
 */
internal fun httpException(code: Int): HttpException = HttpException(
    retrofit2.Response.error<Any>(
        code,
        "{}".toResponseBody("application/json".toMediaType()),
    ),
)

/** Stands in for "no connectivity" — the other retryable failure. */
internal fun ioException(): IOException = IOException("simulated network failure")
