package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.RejectReason

/**
 * Resolves a receipt that the server has already accepted.
 *
 * ## The boundary this interface draws
 *
 * Upload and award are two different problems, and this type is where they separate.
 *
 * *Upload* is a delivery problem: get the bytes to the server, retry on failure, do not
 * duplicate. It is fully implemented against a live API — real POST, real status codes,
 * real idempotency key.
 *
 * *Award* is a **business decision that belongs to the backend**. How many points a
 * receipt is worth, whether it duplicates one already claimed, whether it is fraudulent —
 * no client is allowed to answer those, because a client can be modified by the person
 * holding the phone. A points app whose client decides its own points has no points.
 *
 * DummyJSON has no concept of any of that. So rather than pretending, the award decision
 * sits behind this interface with a simulated implementation, and the seam is honest
 * about which half of the pipeline is real.
 *
 * ## Why this seam is right even with a real backend
 *
 * The important thing about [ReceiptProcessor] is that it is **not a workaround**. It is
 * the interface a production codebase would want anyway:
 *
 * - **Tests cannot wait for a server to award points.** A test for "an awarded receipt
 *   shows its points in the list" needs to force the outcome, not wait twenty seconds and
 *   hope. Anything with a real backend needs a fake at this exact boundary.
 * - **The rejection path is otherwise unreachable.** A healthy backend rejects almost
 *   nothing, so `RejectReason` would be an enum nobody had ever seen rendered. Being able
 *   to force `DUPLICATE` is how the badge, the copy, and the terminal-state handling get
 *   exercised at all.
 * - **It is the same category as stubbing the camera.** Both are boundaries this app does
 *   not own. Stubbing one and not the other would be inconsistent.
 *
 * The framing to use out loud: *point awarding is server-side, so there is an interface at
 * that boundary with a simulated implementation. The upload path is fully real against a
 * live API, including idempotency and retry classification. The simulator also makes the
 * rejection path reachable, which it otherwise would not be.*
 *
 * ## Why polling rather than push
 *
 * A receipt in `PROCESSING` is waiting on a decision only the server can make, so the
 * client either asks or is told. This app asks, on foreground.
 *
 * *Push* is strictly better on the axes that matter: the user sees the award the moment it
 * happens, and the device does no work while nothing is happening. What it costs is a
 * whole subsystem — FCM registration and token rotation, a server-side device registry, a
 * notification permission the user can decline (and on Android 13+ frequently does), a
 * data-message handler that must run without an Activity, and a polling fallback anyway
 * for the users who declined or whose token silently expired. Push does not remove the
 * need to reconcile; it removes the *latency*, and adds a second delivery path to keep
 * correct.
 *
 * Polling on foreground is the right trade here because the only moment resolution matters
 * is when the user is looking at the list. Nobody needs to learn about points while their
 * phone is in a pocket. If this app ever notified, push would earn its keep — and would
 * still keep this poll as the fallback.
 */
internal interface ReceiptProcessor {

    /**
     * Asks whether [receipt] has resolved yet.
     *
     * Takes the domain [Receipt], not the entity: an implementation needs `capturedAt`,
     * `serverId` and the line items, and has no business seeing `attemptCount` or the raw
     * status column. Passing the domain type also means implementations sit above the
     * database and can be tested without one.
     *
     * Must be safe to call repeatedly — it runs on every foreground. Implementations
     * therefore have to be **deterministic for a given receipt**: returning `Awarded(40)`
     * on one poll and `Awarded(65)` on the next would make the displayed total depend on
     * how many times the user opened the app.
     *
     * Failures throw rather than returning an outcome. There is no "poll failed" case in
     * [ReceiptOutcome] on purpose — a failed poll leaves the receipt exactly where it was,
     * which is what [ReceiptOutcome.StillProcessing] already means, and adding an error
     * case would invite callers to write a transition for it.
     */
    suspend fun poll(receipt: Receipt): ReceiptOutcome
}

/**
 * The three answers a poll can give.
 *
 * Deliberately **not** `ReceiptStatus`. That type covers the whole pipeline including
 * `Queued`, `Uploading` and `Failed`, none of which a processor can legitimately return —
 * a poll cannot decide that a receipt was never uploaded. Narrowing the return type to the
 * states this boundary can actually produce means the repository's `when` has three
 * branches instead of six, and three impossible transitions are unrepresentable rather
 * than merely unreachable.
 */
internal sealed interface ReceiptOutcome {

    /** No decision yet. The receipt stays in `PROCESSING` and is polled again later. */
    data object StillProcessing : ReceiptOutcome

    /** Resolved in the user's favour. [points] is the total for the receipt. */
    data class Awarded(val points: Int) : ReceiptOutcome

    /** Resolved against the user. Terminal. */
    data class Rejected(val reason: RejectReason) : ReceiptOutcome
}
