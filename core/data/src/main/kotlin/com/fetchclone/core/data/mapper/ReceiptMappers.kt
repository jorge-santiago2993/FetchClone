package com.fetchclone.core.data.mapper

import com.fetchclone.core.data.database.entity.ReceiptEntity
import com.fetchclone.core.data.database.entity.ReceiptStatusColumn
import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptLineItem
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.model.RejectReason

/**
 * Translation between the stored [ReceiptEntity] and the domain [Receipt].
 *
 * ```
 *   ReceiptEntity  --toDomain()-->  Receipt
 *   (flat columns)                  (sealed ReceiptStatus)
 * ```
 *
 * ## This file is where the "can these columns disagree?" question gets answered
 *
 * `ReceiptEntity` can express states the domain cannot: `AWARDED` with a null
 * `awardedPoints`, `REJECTED` with a garbage `rejectReason`, `FAILED` with no
 * `nextAttemptAt`. Those combinations are unreachable through `ReceiptDao` — every
 * transition writes its payload in the same guarded statement that writes the status —
 * but the *type* still permits them, so something has to decide what happens if one ever
 * appears on disk.
 *
 * That decision is made once, here, and it is deliberately **lenient on read**:
 *
 * - An `AWARDED` row with null points reads as `Awarded(0)`, not a crash.
 * - A `REJECTED` row with an unrecognised reason reads as `Rejected(UNREADABLE)`.
 * - A `FAILED` row with no `nextAttemptAt` reads as eligible immediately.
 * - An unrecognised status string reads as [ReceiptStatus.Queued].
 *
 * ### Why lenient here, when [ReceiptLineItemsCodec] deliberately throws
 *
 * Because the consequences are opposite, and that contrast is the point.
 *
 * Corrupt line items mean we do not know *what the user bought* — presenting that as an
 * empty receipt would show a $0.00 record and award it points, laundering a bug into
 * data. There is no safe reading, so it throws.
 *
 * A malformed status column means we do not know *where in the pipeline* a receipt is,
 * and here there is a safe reading. Throwing would take down `observeAll()` — a `Flow`
 * the whole screen depends on — so one bad row would blank the user's entire receipt
 * history, including the receipts that are fine. Degrading a single row to a sane state
 * keeps the rest of the list alive. The fallbacks are also chosen to be *safe* rather
 * than merely convenient: unknown status falls back to `Queued`, which means "we will try
 * to deliver this again" — the failure mode is a redundant upload that idempotency
 * absorbs, never a silently dropped receipt.
 *
 * ## Why a mapper class was not needed
 *
 * `OfferMappers` argues that a mapper earns a class only when it has dependencies. This
 * one nearly did — it needs a JSON codec — but [ReceiptLineItemsCodec] is a dependency-
 * free `object`, so these stay top-level functions with no DI wiring, callable straight
 * from a unit test.
 *
 * ## Why there is no `Receipt.toEntity()`
 *
 * Deliberately absent, and the omission is load-bearing. A whole-entity write is exactly
 * the blind last-writer-wins overwrite `ReceiptDao` documents as the reason its updates
 * are guarded single-column statements. Receipts are created once by
 * [newReceiptEntity] and thereafter only ever transition through the DAO. Offering a
 * general domain-to-entity mapper would make it easy to write the bug the DAO design
 * exists to prevent.
 */

/**
 * Builds the row for a freshly captured receipt: the only place a [ReceiptEntity] is
 * constructed outside of Room.
 *
 * `attemptCount = 0` and `nextAttemptAt = null` together mean "eligible for upload right
 * now" to `ReceiptDao.findPending`.
 */
internal fun newReceiptEntity(
    id: String,
    capturedAt: Long,
    lineItems: List<ReceiptLineItem>,
): ReceiptEntity = ReceiptEntity(
    id = id,
    capturedAt = capturedAt,
    lineItemsJson = ReceiptLineItemsCodec.encode(lineItems),
    status = ReceiptStatusColumn.QUEUED,
    attemptCount = 0,
    nextAttemptAt = null,
)

internal fun ReceiptEntity.toDomain(): Receipt = Receipt(
    id = id,
    capturedAt = capturedAt,
    lineItems = ReceiptLineItemsCodec.decode(lineItemsJson),
    status = toStatus(),
    serverId = serverId,
)

/**
 * Reassembles the sealed [ReceiptStatus] from the flat columns.
 *
 * The `when` has no `else` over a known set of constants but does need a fallback branch,
 * because `status` is a `String` the compiler cannot constrain — one more reason the
 * domain type, not this column, is what the rest of the app switches on.
 */
private fun ReceiptEntity.toStatus(): ReceiptStatus = when (status) {
    ReceiptStatusColumn.QUEUED -> ReceiptStatus.Queued
    ReceiptStatusColumn.UPLOADING -> ReceiptStatus.Uploading
    ReceiptStatusColumn.PROCESSING -> ReceiptStatus.Processing

    // Points default to 0 rather than throwing on a null: an awarded receipt with an
    // unknown total is still an awarded receipt, and the row is terminal either way.
    ReceiptStatusColumn.AWARDED -> ReceiptStatus.Awarded(points = awardedPoints ?: 0)

    ReceiptStatusColumn.REJECTED -> ReceiptStatus.Rejected(reason = parseRejectReason(rejectReason))

    // `nextAttemptAt ?: 0L` reads as "retry was due at the epoch", i.e. overdue, which is
    // the safe direction: the receipt gets picked up on the next pass rather than being
    // stranded behind a timestamp that was never written.
    ReceiptStatusColumn.FAILED -> ReceiptStatus.Failed(
        attempts = attemptCount,
        nextAttemptAt = nextAttemptAt ?: 0L,
    )

    // Unrecognised status: treat as un-sent rather than as terminal. Losing a receipt is
    // unacceptable; a redundant upload is absorbed by the idempotency key.
    else -> ReceiptStatus.Queued
}

/**
 * `enumValueOf` would throw on an unrecognised name — a real possibility if a future
 * build adds a reason and the user downgrades. [RejectReason.UNREADABLE] is the honest
 * fallback: we know the server refused it, we no longer know why.
 */
private fun parseRejectReason(stored: String?): RejectReason =
    RejectReason.entries.firstOrNull { it.name == stored } ?: RejectReason.UNREADABLE
