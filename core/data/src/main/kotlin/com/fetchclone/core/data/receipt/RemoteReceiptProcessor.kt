package com.fetchclone.core.data.receipt

import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.network.CartsApi
import retrofit2.HttpException
import javax.inject.Inject

/**
 * The [ReceiptProcessor] a real backend would use: re-fetch the receipt and read the
 * server's verdict.
 *
 * ## This class is written but NOT bound
 *
 * `ReceiptModule` binds [SimulatedReceiptProcessor] instead, and says why. This one exists
 * anyway, and that is deliberate rather than dead code:
 *
 * - It makes the seam **honest**. An interface with one implementation is a type nobody
 *   has proven is an abstraction. With both sides written, the boundary is demonstrably in
 *   the right place — swapping implementations is a one-line change in a Hilt module, not
 *   a refactor.
 * - It documents **exactly which half is simulated**. The network call, the 404 handling
 *   and the reconciliation shape are all here and all real. What is missing is only the
 *   part DummyJSON cannot express: a field on the response saying what the server decided.
 *
 * ## The 404 case, which is the interesting one
 *
 * DummyJSON simulates writes without persisting them. `POST /carts/add` returns id 51
 * while only carts 1-50 exist, so re-fetching the id it just handed back always 404s. A
 * real backend would return the record with a resolution status.
 *
 * **A 404 here is mapped to [ReceiptOutcome.StillProcessing], not to an error**, and that
 * is correct behaviour against a real backend too — it is not a workaround for the fake
 * one. In any asynchronously replicated system, write visibility lags acknowledgement: the
 * server accepted the receipt (we hold its id, from a 2xx) and a read routed to a replica
 * that has not caught up yet returns "not found". Treating that as failure would mark
 * healthy receipts as broken during a perfectly ordinary replication lag.
 *
 * The general principle: **"not there yet" and "not a thing" are the same status code but
 * different facts.** The 2xx we already received is what distinguishes them. Absent that,
 * a permanent 404 would indeed be an error — which is why this returns `StillProcessing`
 * only for a receipt that has a `serverId`, a precondition `ReceiptDao.findProcessing`
 * enforces by filtering on `serverId IS NOT NULL`.
 *
 * The cost of the safe reading is a receipt that polls forever if the server truly lost
 * it. A production implementation would bound that — give up after N polls or M days and
 * surface it, the reconciliation loop's equivalent of the upload loop's attempt budget.
 * That is not implemented here because with the simulator bound this code never runs, and
 * writing an untested policy against an imagined backend would be worse than naming the
 * gap.
 *
 * ## What happens when the server and the client disagree
 *
 * This class is where that question lands, so it is worth answering here.
 *
 * **The server wins, always.** Points are the server's to award; the client's copy is a
 * cache of a decision made elsewhere. So every transition driven by a poll overwrites
 * local state, and the DAO's guards exist to control *ordering*, not to arbitrate truth —
 * `markAwarded` requires `PROCESSING` so a late duplicate response cannot re-award, but if
 * the server said `REJECTED` the local row becomes `REJECTED` regardless of what it
 * previously showed.
 *
 * The awkward case is a receipt the client shows as `Awarded` that the server later
 * reverses (fraud review, say). This design cannot represent that — `Awarded` is terminal
 * and never re-polled — so the user would keep seeing points the server has clawed back
 * until the balance came from the server too. The real fix is not a smarter client: it is
 * that a **points balance must be server-authoritative**, and a receipt list is a view of
 * server state rather than a ledger the client maintains. This app has no balance, so the
 * question stays theoretical, but that is the answer.
 */
internal class RemoteReceiptProcessor @Inject constructor(
    private val api: CartsApi,
) : ReceiptProcessor {

    override suspend fun poll(receipt: Receipt): ReceiptOutcome {
        val serverId = receipt.serverId
            // Unreachable via `findProcessing`, which filters these out. Returning rather
            // than throwing keeps one malformed row from stalling the whole reconciliation
            // pass — the same blast-radius argument `classifyUploadFailure` makes.
            ?: return ReceiptOutcome.StillProcessing

        return try {
            api.getCart(serverId)

            // A real backend would return a resolution field here and this would become:
            //
            //   when (response.status) {
            //       "AWARDED"  -> ReceiptOutcome.Awarded(response.points)
            //       "REJECTED" -> ReceiptOutcome.Rejected(response.reason.toRejectReason())
            //       else       -> ReceiptOutcome.StillProcessing
            //   }
            //
            // DummyJSON's cart response carries no such field, so there is nothing to read
            // and nothing honest to infer from a 200. Reporting `StillProcessing` is the
            // truthful answer: the record exists, and we do not know its verdict.
            //
            // Note that the points would come from the RESPONSE, not from a local
            // PointsCalculator call. That is the whole reason this implementation is not
            // bound: a client that computes its own award is a client that can be modified
            // to compute a larger one.
            ReceiptOutcome.StillProcessing
        } catch (e: HttpException) {
            if (e.code() == HTTP_NOT_FOUND) {
                ReceiptOutcome.StillProcessing
            } else {
                // Everything else — 5xx, or a 4xx that is not "not found" — is a genuine
                // failure to poll. Thrown rather than folded into StillProcessing so the
                // caller can tell "the server says wait" apart from "we could not ask".
                // `ReceiptOutcome` has no error case on purpose; see that type.
                throw e
            }
        }
        // IOException is deliberately not caught: no connectivity means we could not ask,
        // which is the caller's problem to absorb, and swallowing it here would make a
        // reconciliation pass silently no-op while looking successful.
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
