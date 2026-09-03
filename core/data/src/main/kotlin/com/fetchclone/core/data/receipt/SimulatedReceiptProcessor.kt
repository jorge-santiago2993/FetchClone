package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.RejectReason
import java.time.Clock
import javax.inject.Inject

/**
 * The [ReceiptProcessor] this app actually runs: it decides awards locally, because the
 * backend has no concept of them.
 *
 * See [ReceiptProcessor] for why the seam exists and why simulating here is a scoping
 * decision rather than a hack. This class is about *how* the simulation stays honest.
 *
 * ## The one property that matters: determinism
 *
 * [poll] runs on every app foreground, potentially dozens of times for the same receipt.
 * A naive simulator — `if (Random.nextInt(100) < 15) Rejected else Awarded` — would be
 * catastrophic here, and in a way that is easy to miss until you see it on a device: the
 * same receipt could poll as `Awarded` once and `Rejected` the next time. The DAO guards
 * would prevent the second write from landing (`markAwarded` requires `PROCESSING`), so
 * the bug would not corrupt data — it would just mean the outcome depended on which poll
 * happened to win. That is a race disguised as a feature.
 *
 * So every decision is derived from the receipt's own id by [stableBucket]. The same
 * receipt always gets the same dwell and the same verdict, forever, with no state stored
 * anywhere. This is the property a real backend would have — a server asked twice about
 * one receipt gives one answer — so simulating it is simulating the right thing.
 *
 * `String.hashCode()` is safe to build on here: its algorithm is *specified* in the Java
 * language spec (`s[0]*31^(n-1) + ...`), not merely implementation-defined, so it is
 * stable across processes, devices and JVM versions. That is not true of `hashCode()` in
 * general, and a simulator keyed off, say, an enum's identity hash would silently stop
 * being reproducible across runs.
 *
 * ## Why a dwell time instead of resolving immediately
 *
 * Returning [ReceiptOutcome.Awarded] on the first poll would make `PROCESSING` a state
 * that exists in the type system and never on screen. The dwell makes the asynchronous
 * shape real: the receipt sits visibly in `Processing`, and resolves later, on a
 * subsequent foreground. That is what the pipeline does with a real backend, and it is
 * what the UI has to be built to handle.
 *
 * The dwell is measured from `capturedAt` against an injected [Clock] — not with `delay()`
 * — so it survives process death. A receipt captured, then abandoned for a week, resolves
 * on the next launch rather than restarting a timer. Retry state living in the database
 * rather than in memory is the same principle applied to the upload loop.
 */
internal class SimulatedReceiptProcessor @Inject constructor(
    private val clock: Clock,
    private val config: SimulatedProcessorConfig,
) : ReceiptProcessor {

    override suspend fun poll(receipt: Receipt): ReceiptOutcome {
        val elapsed = clock.millis() - receipt.capturedAt

        // Guard against a receipt captured "in the future" — a device clock adjustment, or
        // an NTP correction between capture and poll, makes `elapsed` negative. Without
        // this the receipt would be stuck in PROCESSING until wall-clock time caught up.
        // Real systems hit this; it is the standard argument for a monotonic clock, which
        // is unavailable here because the timestamp has to survive a reboot.
        if (elapsed < 0) return ReceiptOutcome.StillProcessing

        if (elapsed < dwellMillisFor(receipt.id)) return ReceiptOutcome.StillProcessing

        return if (isRejected(receipt.id)) {
            // DUPLICATE specifically, not a random reason. It is the only rejection a
            // client could plausibly attribute without server knowledge, and it is the one
            // that maps to something a user recognises: they scanned the same receipt
            // twice. Cycling through TOO_OLD and NOT_A_RECEIPT would render badges the
            // simulation has no basis for claiming.
            ReceiptOutcome.Rejected(RejectReason.DUPLICATE)
        } else {
            ReceiptOutcome.Awarded(points = PointsCalculator.award(receipt.lineItems))
        }
    }

    /**
     * A stable dwell in `[minDwellMillis, maxDwellMillis]` for this receipt.
     *
     * Varied per receipt rather than fixed so that a batch submitted together does not
     * resolve in one synchronised block — which would look like a bug, and would hide
     * ordering issues that a staggered resolution exposes.
     */
    private fun dwellMillisFor(receiptId: String): Long {
        val span = config.maxDwellMillis - config.minDwellMillis
        return config.minDwellMillis + stableBucket(receiptId, DWELL_SALT, span.toInt() + 1)
    }

    /**
     * Whether this receipt is in the rejected slice.
     *
     * A different salt from [dwellMillisFor] on purpose: reusing one would correlate the
     * two derivations, so every rejected receipt would also have a short dwell. Salting
     * makes them independent, the way two unrelated server decisions would be.
     */
    private fun isRejected(receiptId: String): Boolean =
        stableBucket(receiptId, REJECT_SALT, 100) < config.rejectionRatePercent

    /**
     * Maps [id] into `[0, buckets)` deterministically.
     *
     * `Int.mod` rather than `%`: the remainder operator preserves sign, so a negative hash
     * — roughly half of them — would yield a negative bucket and either crash an index or
     * silently disable a threshold comparison. `mod` is always non-negative. This is a
     * small thing that is wrong in a lot of production code.
     */
    private fun stableBucket(id: String, salt: String, buckets: Int): Int =
        (id + salt).hashCode().mod(buckets)

    private companion object {
        const val DWELL_SALT = ":dwell"
        const val REJECT_SALT = ":reject"
    }
}

/**
 * Tuning for [SimulatedReceiptProcessor]. Provided by `ReceiptModule`.
 *
 * A data class with defaults rather than constants on the processor, so the values can be
 * changed in one `@Provides` for a demo, and so tests can construct a processor that
 * rejects everything (or nothing) without touching production defaults. A test that wants
 * to see the rejection path uses `rejectionRatePercent = 100`, which is far clearer than
 * hunting for a receipt id that happens to hash into the slice.
 *
 * @property minDwellMillis / [maxDwellMillis] the 10-20 second window from the spec. Long
 *   enough that `PROCESSING` is visibly a real state, short enough to watch it resolve
 *   without putting the app down.
 * @property rejectionRatePercent share of receipts resolved as
 *   [RejectReason.DUPLICATE], in `[0, 100]`. Defaulted to 15 because a
 *   `RejectReason` enum that has never been observed on screen is one nobody can speak to
 *   — the point is to *see* a rejected receipt in the list, not to model a real rejection
 *   rate, which for a healthy backend would be far lower and effectively never show up.
 */
internal data class SimulatedProcessorConfig(
    val minDwellMillis: Long = 10_000,
    val maxDwellMillis: Long = 20_000,
    val rejectionRatePercent: Int = 15,
)
