package com.fetchclone.core.data.model

/**
 * A submitted receipt as the **feature layer** sees it.
 *
 * ## Why the domain model splits `status` out of the entity's flat columns
 *
 * `ReceiptEntity` stores the state machine as several loosely-related nullable columns
 * (`status`, `attemptCount`, `nextAttemptAt`, `awardedPoints`, `rejectReason`) because
 * that is what SQLite can express. Those columns can represent nonsense: a row that is
 * `AWARDED` *and* carries a `rejectReason`, or a `FAILED` row with no `nextAttemptAt`.
 * [ReceiptStatus] collapses them into a closed set of states where each one carries
 * exactly the data that state can have, and nothing else is representable.
 *
 * The mapper (`ReceiptMappers`) is the one place that translates between the two, so
 * "can these columns disagree?" has exactly one answer to audit.
 *
 * **This is the point of requirement 7's "status badge driven by the sealed type, not a
 * string".** A UI that switches on `entity.status == "AWARDED"` then has to reach for
 * `entity.awardedPoints!!` and hope. A UI that switches on [ReceiptStatus.Awarded] gets
 * `points: Int` handed to it by the type system.
 *
 * ### Why this lives in `:core:data` and not a `:core:model` module
 *
 * Same call, and the same reasoning, as [Offer] — see that class. `:feature:receipts`
 * depends on `:core:data` for exactly these domain types plus the repository interface,
 * and never sees Room, Retrofit or WorkManager.
 *
 * @property id the client-generated UUID. It is the Room primary key **and** the
 *   `Idempotency-Key` sent to the server; see `ReceiptEntity` for why those are one value.
 * @property capturedAt when the user "scanned" the receipt, epoch millis. The list's
 *   sort key, and the clock the simulator's dwell time is measured from.
 * @property lineItems what was on the receipt. Never empty.
 * @property status where this receipt is in the submission state machine.
 * @property serverId the id the backend assigned once the upload was accepted; null
 *   until then. Reconciliation needs it to re-fetch the record.
 */
data class Receipt(
    val id: String,
    val capturedAt: Long,
    val lineItems: List<ReceiptLineItem>,
    val status: ReceiptStatus,
    val serverId: String? = null,
) {
    /** Receipt total in integer cents. Derived, never stored — one source of truth. */
    val totalCents: Long get() = lineItems.sumOf { it.totalCents }
}

/**
 * One product on a receipt.
 *
 * ### Why line items are stored as JSON in a column rather than a child table
 *
 * A `receipt_line_items` table with a foreign key is the textbook-normalised answer, and
 * it would be right if line items were ever queried *independently* — "how many people
 * bought product 42 this week", or anything wanting an index on `productId`. They are
 * not. Line items are written once, at capture, and read only as part of their receipt.
 * For that access pattern a JSON column wins on three counts:
 *
 * 1. **One row, one write.** `submit()` is on the user's critical path — it *is* the
 *    commit. A single `INSERT` beats an insert plus N inserts in a transaction.
 * 2. **No join, no `@Relation`, no partial reads.** Loading a receipt cannot
 *    accidentally leave its line items behind.
 * 3. **A receipt is an immutable record.** Nothing ever mutates one line in isolation.
 *
 * **The cost, stated honestly:** the column is opaque to SQL. We cannot query, index or
 * `ALTER TABLE` individual fields, and changing this class's shape means hand-writing a
 * JSON migration instead of a schema one. That is the trade. If a "purchase history by
 * product" feature ever lands, normalise it then — the mapper is the only thing that
 * would have to change.
 *
 * ### Why this snapshots the offer instead of pointing at it
 *
 * Every field here is a **copy taken at capture time**, not a lookup key. The obvious
 * alternative is to store only `productId` and read the rest out of the `offers` table
 * when it is needed. That is normal practice for reference data, and it is wrong here for
 * two separate reasons:
 *
 * 1. **The row would not always be there.** `OffersRemoteMediator` calls `clearAll()` on
 *    every successful REFRESH and repopulates only the page it fetched, so a product from
 *    a deeper page can be absent from the cache between capture and award. Point totals
 *    would then depend on where the user happened to have scrolled — reference data moving
 *    under an award turns it into a race.
 * 2. **It would be the wrong answer even if the row were there.** A receipt records a past
 *    event. If a 40%-off promotion ends the day after a purchase, the points earned on
 *    that purchase do not retroactively shrink. Reading the *current* offer computes what
 *    the receipt would be worth today, which is not the question being asked.
 *
 * Note that stale identity is *not* among the reasons: `OfferEntity.id` is the product id
 * and is stable across refreshes, so there is no risk of a product being re-identified and
 * no need to snapshot extra matching keys. What is unstable is the row's *presence* and
 * its *promotional terms*, and those are exactly what gets frozen here.
 *
 * The cost is denormalisation — a title stored on the receipt will not follow a later
 * rename of the product. For an immutable historical record that is the desired behaviour,
 * not a defect. Only the fields the receipt actually needs are copied: display (`title`,
 * `unitPriceCents`) and the award input ([discountPercentage]). Copying the whole `Offer`
 * would add columns nothing reads.
 *
 * @property unitPriceCents integer cents, never `Double` — the same rule as
 *   [Offer.priceCents]. Money in floating point accumulates rounding error and compares
 *   incorrectly.
 * @property discountPercentage the promotional discount **as it stood when the receipt was
 *   captured**, e.g. `12.5` for 12.5% off. `PointsCalculator` reads this and only this to
 *   decide whether the line qualifies for a partner bonus, which makes awards deterministic
 *   and independent of cache state. `Double` matches [Offer.discountPercentage]; it is a
 *   rate, not money, so the integer-cents rule does not apply.
 */
data class ReceiptLineItem(
    val productId: Int,
    val title: String,
    val quantity: Int,
    val unitPriceCents: Long,
    val discountPercentage: Double,
) {
    /** Extended price for this line. */
    val totalCents: Long get() = unitPriceCents * quantity
}

/**
 * Where a receipt is in the submission state machine.
 *
 * ```
 *                 submit()  <- THE LOCAL WRITE IS THE COMMIT
 *                     |
 *                     v
 *   +------------>  Queued
 *   |                 |  processQueue() selects it
 *   |                 v
 *   |             Uploading
 *   |                 |  POST /carts/add  (Idempotency-Key: receipt.id)
 *   |     +-----------+------------+
 *   |     | 2xx       | 4xx        | 5xx / IOException
 *   |     v           v            v
 *   | Processing   Rejected    Failed(attempts, nextAttemptAt)
 *   |     |        TERMINAL         |
 *   |     |                         +-- attempts <  MAX -> retry after backoff --+
 *   |     |  ReceiptProcessor       |                                            |
 *   |     |  .poll()                +-- attempts >= MAX -> TERMINAL failure       |
 *   |     v                                                                      |
 *   | Awarded(points) | Rejected(reason)                                         |
 *   |   TERMINAL          TERMINAL                                               |
 *   |                                                                            |
 *   +----------------------------------------------------------------------------+
 *
 *   Process death while Uploading -> row is recovered back to Queued on next start.
 * ```
 *
 * ### Why a `sealed interface` rather than an enum plus side fields
 *
 * An enum cannot carry per-state data, so the awarded point total and the reject reason
 * would have to sit beside it as nullables meaningful for exactly one member — the
 * representable-nonsense problem described on [Receipt]. A sealed hierarchy makes the
 * compiler enforce "points exist if and only if we are Awarded", and gives an exhaustive
 * `when` with no `else`, so adding a state later is a compile error at every site that
 * must handle it rather than a silent fallthrough into a default branch.
 *
 * ### Why `Failed` is not split into "retrying" and "gave up"
 *
 * Exhaustion is **derived, not stored**: a receipt is permanently failed when
 * [Failed.attempts] reaches [MAX_UPLOAD_ATTEMPTS], and `ReceiptDao.findPending` enforces
 * it with `attemptCount < :maxAttempts` so an exhausted row is simply never selected
 * again. The alternative — a distinct `FAILED_PERMANENT` status string — would record
 * the same fact in two places (the status column and the counter) and invite them to
 * disagree. One source of truth for "should we try again": the counter.
 */
sealed interface ReceiptStatus {

    /**
     * On disk, not yet sent. **This is the state `submit()` returns at.**
     *
     * Reaching it is the entire user-visible promise: the receipt is durable, survives
     * process death and reboot, and will be delivered eventually. Everything past this
     * point is the app's problem, not the user's. That is what "the local write is the
     * commit" buys — submission succeeds in a few milliseconds on a subway platform.
     */
    data object Queued : ReceiptStatus

    /**
     * A `POST /carts/add` is in flight for this receipt right now.
     *
     * Transient, but **persisted anyway**, and that matters: if the process dies here we
     * cannot know whether the server received the request. Recovery is to reset the row
     * to [Queued] and send it again — safe *precisely because* the request carries an
     * `Idempotency-Key`, so a duplicate delivery is a server-side no-op. Without
     * idempotency this state would be unrecoverable and we would have to choose between
     * losing receipts and double-awarding them.
     */
    data object Uploading : ReceiptStatus

    /**
     * The server accepted the upload and is deciding the award asynchronously.
     *
     * Not terminal, but not retryable either — re-POSTing would be wrong, since the
     * server already has the receipt. This state is resolved by *polling*
     * (`ReceiptProcessor.poll`), never by the upload loop. Keeping the two loops
     * separate is why a stuck award can't cause a duplicate upload.
     */
    data object Processing : ReceiptStatus

    /** Terminal success. [points] is an `Int` — points are counted, never measured. */
    data class Awarded(val points: Int) : ReceiptStatus

    /**
     * Terminal failure a retry cannot fix: the server looked at this receipt and said
     * no. Categorically different from [Failed], which means "we could not ask".
     * Conflating the two is how apps end up retrying a permanent rejection forever.
     */
    data class Rejected(val reason: RejectReason) : ReceiptStatus

    /**
     * Delivery failed for a reason that might not recur — a 5xx, or no connectivity.
     *
     * @property attempts how many times we have tried, already including the failure
     *   that produced this state.
     * @property nextAttemptAt epoch millis before which the processor must not retry.
     *   **Persisted, not held in memory**: the whole point of an outbox is surviving the
     *   process, so the schedule has to survive it too. A retry timer living in a
     *   coroutine dies with the app, and the receipt would then either retry instantly
     *   on next launch — hammering a server that is already struggling — or never.
     */
    data class Failed(val attempts: Int, val nextAttemptAt: Long) : ReceiptStatus {

        /** True once the retry budget is spent; the processor will not select this row again. */
        val isExhausted: Boolean get() = attempts >= MAX_UPLOAD_ATTEMPTS
    }

    companion object {
        /**
         * Retry budget per receipt, from the spec's `attemptCount >= 5 -> terminal failure`.
         *
         * It lives on the domain type rather than inside the repository because the UI
         * needs it too: the status badge reads "Retrying" below this number and
         * "Couldn't send" at it. A feature module reaching into a data-layer
         * implementation constant would be the kind of leak the module split exists to
         * prevent.
         */
        const val MAX_UPLOAD_ATTEMPTS = 5
    }
}

/**
 * Why the server refused a receipt.
 *
 * These are *business* rejections, deliberately not HTTP status codes: the UI says "We
 * couldn't read that receipt", never "422". `ReceiptErrorClassifier` owns the single
 * mapping from status code to reason, so HTTP vocabulary stops at the data layer's edge
 * — the same discipline that keeps Retrofit types out of `FeedRefreshState`.
 */
enum class RejectReason {
    /** The server already awarded this purchase — often a genuinely re-scanned receipt. */
    DUPLICATE,

    /** The capture was too poor to parse. In a real app, the OCR's verdict. */
    UNREADABLE,

    /** Outside the submission window — Fetch's real product uses 14 days. */
    TOO_OLD,

    /** Parsed fine, but it is not a receipt. */
    NOT_A_RECEIPT,
}
