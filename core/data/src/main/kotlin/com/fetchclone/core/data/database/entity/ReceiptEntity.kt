package com.fetchclone.core.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A receipt in the submission **outbox**.
 *
 * ## This table is not a cache
 *
 * `offers` is a cache: every row can be re-fetched from the server, so dropping it costs
 * a round-trip and nothing else. `receipts` is the opposite. A row here may be the *only
 * copy in existence* of something the user did — they scanned a receipt on a subway
 * platform, we told them it succeeded, and the server has never heard of it. Losing the
 * row breaks a promise we already made.
 *
 * That difference drives two decisions elsewhere:
 * - `FetchCloneDatabase` has hand-written migrations and **no** destructive fallback.
 *   See the doc there.
 * - `ReceiptDao` has no "delete all" method. The scaffolding version of this DAO had
 *   one, which is correct for a cache and quietly dangerous for an outbox.
 *
 * ## Why the primary key is client-generated, and why it doubles as the idempotency key
 *
 * The obvious alternative — auto-increment locally, let the server assign the real id —
 * cannot work here, because **the row exists before the server knows about it**. The
 * local write is the commit; there may be no network for hours. So the client has to
 * mint an identifier, and a UUID is the standard collision-free way to do that without
 * coordination.
 *
 * Having minted one, sending it as the `Idempotency-Key` header is free, and it is what
 * makes the whole pipeline safe:
 *
 * - The upload is **retryable without being duplicative**. If we POST, the response is
 *   lost to a dropped connection, and we retry, the server recognises the key and
 *   returns the original result instead of awarding points twice.
 * - [ReceiptStatus.Uploading] becomes **recoverable after process death**. We genuinely
 *   cannot know whether an in-flight request arrived; idempotency turns that unanswerable
 *   question into an unimportant one, so recovery is simply "send it again".
 * - The key is **stable across app reinstalls of the retry**, because it is the primary
 *   key of a durable row rather than something generated per attempt. Generating a fresh
 *   key per attempt would defeat the entire mechanism — a classic way to get this wrong.
 *
 * *What breaks if two devices submit the same physical receipt:* nothing here saves you.
 * Two devices mint two different UUIDs, so the server sees two distinct idempotency keys
 * and must deduplicate on receipt *content* — the merchant, total and timestamp — not on
 * our key. Idempotency keys defend against **redelivery of one submission**, not against
 * **two independent submissions of one real-world event**. That is the server's
 * `DUPLICATE` rejection, which is exactly why [rejectReason] exists.
 *
 * ## Why the state machine is stored as flat columns
 *
 * Room persists rows, not sealed hierarchies. `status` is the discriminator and the rest
 * are per-state payloads that are null in states where they do not apply. This shape can
 * represent nonsense (`AWARDED` with a `rejectReason`); `ReceiptStatus` cannot, and
 * `ReceiptMappers` is the single audited crossing between the two. Storing a serialized
 * `ReceiptStatus` blob instead would remove the nonsense but make `status` unqueryable —
 * and `findPending` has to filter on it in SQL, so that is not an option.
 *
 * ## Indexing
 *
 * Unlike `OfferEntity`, this table **does** carry an index, because unlike that table it
 * has a hot filtered query. `findPending` runs `WHERE status IN (...) AND (nextAttemptAt
 * IS NULL OR nextAttemptAt <= ?)` on every processor pass — on submit, on every app
 * foreground, and on every WorkManager run. Without an index that is a full table scan
 * over a table that only grows. The index is on `status` because it is the selective
 * leading predicate; `nextAttemptAt` is a range check that SQLite applies afterwards.
 *
 * The write cost is one index update per status transition, which is real but tiny next
 * to the network call each transition accompanies.
 *
 * @property id client-generated UUID; primary key and `Idempotency-Key`. See above.
 * @property capturedAt epoch millis of the "scan". Sort key for the list.
 * @property lineItemsJson the receipt's line items, JSON-encoded. See [ReceiptLineItem]
 *   for why this is a blob rather than a child table.
 * @property status the state-machine discriminator, one of `ReceiptStatusColumn`'s
 *   constants. Stored as `String` rather than an enum ordinal so the values survive
 *   reordering the enum, and so the table is readable in a DB inspector.
 * @property attemptCount upload attempts so far. The single source of truth for "have we
 *   given up" — see `ReceiptStatus.Failed.isExhausted`.
 * @property nextAttemptAt epoch millis before which the processor must not retry; null
 *   means "eligible now". Persisted rather than held in a coroutine timer so the retry
 *   schedule survives process death.
 * @property serverId the backend's id for this receipt, once accepted. Reconciliation
 *   re-fetches with it. `String?` rather than `Int?` because a real backend would hand
 *   back an opaque identifier; DummyJSON's numeric cart id is converted at the edge.
 * @property awardedPoints non-null only in the `AWARDED` state. `Int` — points are
 *   counted, never measured.
 * @property rejectReason non-null only in the `REJECTED` state; a [RejectReason] name.
 */
@Entity(
    tableName = "receipts",
    indices = [Index(value = ["status"])],
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val capturedAt: Long,
    val lineItemsJson: String,
    // No @ColumnInfo(defaultValue = ...) on `status` / `attemptCount`. Column defaults
    // earn their keep when a later migration does ALTER TABLE ADD COLUMN on a NOT NULL
    // column, which needs one. Nothing inserts a receipt without an explicit status —
    // `submit()` is the only writer — so a default would be schema surface we never
    // exercise, and one more thing the hand-written migration DDL has to match exactly.
    // Same discipline as OfferEntity's "no indexes we don't query".
    val status: String,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val serverId: String? = null,
    val awardedPoints: Int? = null,
    val rejectReason: String? = null,
)

/**
 * The exact strings stored in [ReceiptEntity.status].
 *
 * ### Why `const val` strings instead of an enum with a Room `@TypeConverter`
 *
 * A `@TypeConverter` would be tidier at the entity, but these values have to appear
 * **inside SQL string literals** in `ReceiptDao` — `WHERE status IN ('QUEUED', 'FAILED')`
 * — and Room's `@Query` strings are compile-time constants that a converter cannot reach
 * into. `const val` lets the DAO interpolate the same identifiers the mapper writes, so
 * a typo is a compile error rather than a query that silently matches nothing. That last
 * failure mode is worth dwelling on: a misspelled status in a `WHERE` clause does not
 * throw, it just quietly stops draining the outbox.
 *
 * These are storage identifiers, not display strings and not the domain type. Nothing
 * outside `ReceiptMappers` and `ReceiptDao` should mention them.
 */
object ReceiptStatusColumn {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val PROCESSING = "PROCESSING"
    const val AWARDED = "AWARDED"
    const val REJECTED = "REJECTED"
    const val FAILED = "FAILED"
}
