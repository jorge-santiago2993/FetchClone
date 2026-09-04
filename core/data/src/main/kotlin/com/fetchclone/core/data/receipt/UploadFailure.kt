package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.RejectReason
import retrofit2.HttpException
import java.io.IOException

/**
 * What the processor should do about a failed upload attempt.
 *
 * This type exists to make the spec's sharpest constraint — *"4xx is terminal. 5xx and IO
 * are retryable. Do not conflate them."* — impossible to get wrong by accident. The
 * decision is made once, in [classifyUploadFailure], and the call site does an exhaustive
 * `when` over the result rather than re-inspecting exception types.
 */
internal sealed interface UploadFailure {

    /**
     * The server understood the request and refused it. **Terminal — never retried.**
     *
     * The distinction that matters: a 4xx is a *verdict*, not an outage. The request was
     * delivered, parsed, and judged. Sending it again produces the same verdict, so a
     * retry is pure cost — battery, data, and server load — and worse, it hides a real
     * failure behind a loop that looks busy. A receipt the server has rejected needs a
     * human decision, not another attempt.
     */
    data class Rejected(val reason: RejectReason) : UploadFailure

    /**
     * We could not get a verdict. **Retryable, with backoff, up to the attempt budget.**
     *
     * Covers a broken server (5xx) and a broken connection (IO). They are one category
     * here because the correct response is identical — wait and try again — even though
     * they feel different. The thing they share is the thing that matters: *the outcome is
     * unknown*, so the receipt might yet succeed.
     *
     * Note the asymmetry this creates with [Rejected], and that it is the safe direction.
     * Misclassifying a retryable failure as terminal **loses a receipt permanently**.
     * Misclassifying a terminal failure as retryable costs at most five wasted requests
     * before the attempt budget makes it terminal anyway. When the classification is
     * genuinely unknown, retryable is the answer — see [classifyUploadFailure].
     */
    data object Retryable : UploadFailure

    /**
     * There was no network to attempt over. **Retryable, and it must NOT consume an
     * attempt.**
     *
     * The distinction from [Retryable] is the one this whole file exists to draw, and it
     * was originally missing — with real consequences. Both arrive as `IOException`, but
     * they mean opposite things about the *receipt*:
     *
     * - [Retryable] — we asked and it went wrong. Evidence about this delivery, so it is
     *   fair to spend one of the receipt's five attempts on it.
     * - [Deferred] — **we never asked.** No radio, no request, no evidence about anything.
     *   Counting it charges the receipt for the user being on a plane.
     *
     * Reproduced before the fix: airplane mode, one scan, six app foregrounds → five POSTs
     * with no radio, budget exhausted, badge "Couldn't send", and the receipt was still
     * dead after reconnecting because `findPending` filters on
     * `attemptCount < :maxAttempts`. Silent data loss on a valid receipt.
     *
     * The handling is to leave the row exactly as it was — `QUEUED`, counter untouched,
     * no backoff armed — and let the `NetworkType.CONNECTED`-constrained worker pick it up
     * when the OS says connectivity is back.
     *
     * **This deviates from the spec's literal `5xx / IO -> FAILED, attemptCount++`.** It is
     * a deliberate departure: the spec's *intent* is to bound futile retries, and a receipt
     * that has never been transmitted is not a futile retry, it is an untried one.
     */
    data object Deferred : UploadFailure

    /**
     * There is no valid session to upload under. **Retryable, and it must NOT consume an
     * attempt.**
     *
     * ### The bug this type exists to prevent
     *
     * Before authentication, this file said of 401: *"retryable after a token refresh,
     * which makes it neither terminal nor plain-retryable but a third thing: recoverable by
     * side effect… not special-cased here because this app has no auth."* The app has auth
     * now, and without this branch the prediction comes true in the worst way.
     *
     * A 401 is a 4xx, so [classifyUploadFailure] would have called it [Rejected]. The
     * receipt would be marked terminally `REJECTED`, and the user would be told **their
     * receipt was refused** — a permanent, false verdict about their shopping, caused by
     * their session expiring. It is the airplane-mode bug wearing a different hat: a
     * problem that has nothing to do with the receipt, charged to the receipt.
     *
     * ### Why a 401 reaching here at all means the session is dead
     *
     * `TokenAuthenticator` intercepts 401s beneath Retrofit and replays the request with a
     * fresh token. A 401 that surfaces to this classifier is therefore one that survived
     * that: refresh was attempted and failed, or there was nothing to refresh with. Either
     * way the credential problem is not solvable by retrying now — but it *is* solvable, by
     * the user signing in again.
     *
     * So the handling matches [Deferred]: leave the row `QUEUED`, do not touch
     * `attemptCount`, arm no backoff. The receipt waits for a session the way an offline
     * receipt waits for a radio. `processQueue` also refuses to start a pass while signed
     * out, so this is the narrow race — a session dying mid-pass — rather than the common
     * path.
     *
     * ### Why not simply reuse [Deferred]
     *
     * The row treatment is identical, so one type would work. Two exist because the
     * *diagnosis* differs, and these types are read as an explanation of what happened as
     * much as an instruction: [Deferred] means "no radio", this means "no session". A log
     * or a future UI message that conflated them would send a signed-out user to check
     * their connection.
     */
    data object Unauthenticated : UploadFailure
}

/**
 * Maps a thrown exception to the outbox's decision.
 *
 * ## The HTTP status mapping
 *
 * | Status | Result | Why |
 * |---|---|---|
 * | 409 Conflict | [RejectReason.DUPLICATE] | the canonical "already exists" code |
 * | 410 Gone | [RejectReason.TOO_OLD] | outside the submission window |
 * | 422 Unprocessable | [RejectReason.UNREADABLE] | well-formed request, unusable content |
 * | 400 Bad Request | [RejectReason.NOT_A_RECEIPT] | the payload is not what this endpoint takes |
 * | other 4xx | [RejectReason.UNREADABLE] | refused for a reason we have no vocabulary for |
 * | 5xx | [UploadFailure.Retryable] | the server broke; the receipt may still be fine |
 *
 * These pairings are invented, because DummyJSON does not define them — a real
 * integration replaces this table with whatever the backend documents, and that is the
 * point of having it in one function. What is *not* invented is the 4xx/5xx split, which
 * is HTTP semantics: 4xx means the client's request is at fault, 5xx means the server is.
 *
 * ### 401 and 429 are the two worth arguing about
 *
 * Both are 4xx, and neither belongs in the terminal bucket. This comment used to say that
 * neither was special-cased "because this app has no auth" — one of those two has since
 * come true, which is a decent argument for writing down the exceptions you are choosing
 * not to handle yet.
 *
 * - **401 Unauthorized** is retryable *after* a token refresh, which makes it neither
 *   terminal nor plain-retryable but a third thing: recoverable by side effect. It is now
 *   handled, as [UploadFailure.Unauthenticated], and the branch is placed above the 4xx
 *   sweep so it cannot be swallowed by it.
 * - **429 Too Many Requests** is explicitly a "try again later" signal, with the delay
 *   often given in `Retry-After`. It is a 4xx that is unambiguously retryable — the single
 *   clearest counterexample to "4xx is terminal", and the reason that rule is a strong
 *   default rather than a law. Still not special-cased, because DummyJSON has no rate
 *   limit: the branch would be written against an imagined contract and exercised only by
 *   a fake. The right time to add it is when a backend actually sends one.
 *
 * ## The connectivity split
 *
 * An `IOException` alone cannot tell you whether the request failed or was never made, and
 * those need opposite handling — see [UploadFailure.Deferred]. [isOnline] is what
 * separates them.
 *
 * @param isOnline whether a validated connection exists **at the moment of classification**,
 *   from `NetworkMonitor`. Read after the failure rather than before it, because that is the
 *   question being asked: connectivity can drop between the pre-flight check in
 *   `processQueue` and the request completing, and a receipt must not be charged for a
 *   window that closed underneath it.
 */
internal fun classifyUploadFailure(error: Throwable, isOnline: Boolean): UploadFailure = when (error) {
    is HttpException -> when (val code = error.code()) {
        // 401 is checked BEFORE the 4xx sweep, and the ordering is the whole point: it is
        // a 4xx that must not be treated as a verdict on the receipt. See
        // UploadFailure.Unauthenticated.
        HTTP_UNAUTHORIZED -> UploadFailure.Unauthenticated
        in 400..499 -> UploadFailure.Rejected(rejectReasonForStatus(code))
        // 5xx, and anything else non-2xx that Retrofit surfaced as an HttpException.
        else -> UploadFailure.Retryable
    }

    // DNS failure, socket or read timeout, connection reset, or no radio at all. The
    // request may or may not have reached the server — which is exactly why the
    // idempotency key exists, and why retrying is safe either way.
    //
    // The connectivity check is what splits this one exception type into the two very
    // different situations documented on `Deferred`. Note the direction of the fallback:
    // when unsure we treat it as a real attempt, because over-counting costs a retry while
    // under-counting could let a genuinely broken receipt loop forever.
    is IOException -> if (isOnline) UploadFailure.Retryable else UploadFailure.Deferred

    // Anything else is a bug rather than a network condition: a serialization failure, an
    // NPE, a Retrofit misconfiguration.
    //
    // Treating it as retryable is a deliberate departure from how `OffersRemoteMediator`
    // handles the same situation — it catches only IOException and HttpException and lets
    // everything else propagate, so a bug surfaces as a crash instead of masquerading as a
    // network blip. That is right *there* and wrong *here*, because the blast radius is
    // different. The mediator runs inside Paging, which turns a throw into a LoadState the
    // user can retry. This loop runs in an application-scoped coroutine, where an escaping
    // exception takes down the process, and it runs for *all* receipts — so one malformed
    // response would stop the entire outbox from draining, including receipts that are
    // perfectly fine.
    //
    // Retryable (not Deferred) is right here: an unexpected exception IS evidence that
    // something about this delivery went wrong, so it should cost an attempt.
    // Retryable is safe rather than sloppy: the attempt budget bounds it to five tries and
    // then the receipt becomes terminally failed and visible in the UI, so a persistent
    // bug surfaces as a stuck receipt rather than being silently swallowed. It is not
    // marked Rejected, because telling the user the server refused their receipt when in
    // fact we failed to parse a response would be a lie the UI then repeats.
    //
    // CancellationException deliberately does not reach here — the caller re-throws it
    // before classifying. See `DefaultReceiptRepository.uploadOne`.
    else -> UploadFailure.Retryable
}

private const val HTTP_UNAUTHORIZED = 401

private fun rejectReasonForStatus(code: Int): RejectReason = when (code) {
    409 -> RejectReason.DUPLICATE
    410 -> RejectReason.TOO_OLD
    422 -> RejectReason.UNREADABLE
    400 -> RejectReason.NOT_A_RECEIPT
    else -> RejectReason.UNREADABLE
}
