package com.fetchclone.core.data.network.model

import kotlinx.serialization.Serializable

/**
 * Request body for `POST /carts/add`.
 *
 * ## What this endpoint is standing in for
 *
 * DummyJSON has no receipt-submission API, so the cart endpoint plays that role: it
 * accepts a set of product ids with quantities, which is structurally what a parsed
 * receipt is. The *upload path is genuinely real* — a live HTTP call, real status codes,
 * real timeouts, a real idempotency header — and that is the part of the pipeline this
 * project is actually about. Only the award decision is stubbed, behind
 * `ReceiptProcessor`. See the spec's addendum.
 *
 * ## Why `userId` has NO Kotlin default value
 *
 * It did have one, and that was a real bug — caught only by running the app against the
 * live API, where every POST came back `400 {"message":"User id is required"}`.
 *
 * **kotlinx.serialization does not encode default values.** `encodeDefaults` is `false`
 * out of the box, so `val userId: Int = 1` is a property the encoder *skips*: the field
 * never appears on the wire at all. The Kotlin object had `userId = 1`; the JSON had no
 * `userId` key.
 *
 * This is a nasty class of bug and worth being able to name:
 * - **Invisible in the debugger.** Inspect the `CartRequest` and `userId` is 1. Only the
 *   serialized bytes are wrong, and nothing in the app shows you those.
 * - **Only fails against a server that validates.** A lenient backend would substitute its
 *   own default and the mistake would ship, surfacing much later as data attributed to the
 *   wrong user — which is far worse than a 400.
 * - **The compiler cannot help.** A Kotlin default and a wire-required field look
 *   identical in the source.
 *
 * The rule it is an instance of: **a Kotlin default cannot populate a field the protocol
 * requires.** Defaults are a language convenience for callers; serialization is a contract
 * with a remote system. The two are unrelated, and it is easy to assume otherwise.
 *
 * Dropping the default makes the requirement structural — the compiler now forces every
 * call site to supply a user id, so the field cannot silently go missing again.
 *
 * **Alternatives considered and declined:** `@EncodeDefault` on the property is the
 * purpose-built fix and keeps call sites terser, but it is `@ExperimentalSerializationApi`
 * and it leaves the hazard one deleted annotation away from returning. Setting
 * `encodeDefaults = true` on the shared `Json` would also work, and was declined as a
 * global change made to fix one DTO — it would start emitting defaults for every other
 * model too, changing payloads nobody was looking at.
 *
 * @property userId the submitting account. Callers pass [DEFAULT_USER_ID]: there is no
 *   auth in this app, and inventing an account system to satisfy a field a fake backend
 *   barely checks would be scope with no payoff. In a real client this comes from the
 *   session.
 */
@Serializable
data class CartRequest(
    val userId: Int,
    val products: List<CartProductRequest>,
) {
    companion object {
        /** DummyJSON ships 30 users; 1 always exists. */
        const val DEFAULT_USER_ID = 1
    }
}

/**
 * One line of the submitted cart.
 *
 * Only `id` and `quantity` are sent — the server already knows every product's price, and
 * a client that told the server what things cost would be trusting the client with money.
 * That is worth noting even here: the request carries *what was bought*, never *what it
 * was worth*. The price on our [ReceiptLineItem] is a local display and award input, not
 * an assertion to the backend.
 */
@Serializable
data class CartProductRequest(
    val id: Int,
    val quantity: Int,
)

/**
 * Response from `POST /carts/add`, and from `GET /carts/{id}`.
 *
 * Only the fields the pipeline uses are declared; the shared `Json` ignores the rest
 * (`products`, `total`, `discountedTotal`, ...). Deliberately minimal: every field
 * declared here is one more thing that can break parsing when the backend changes, and
 * the only thing this app needs from the response is the identifier to reconcile against.
 *
 * @property id the server's cart id. Stored as `ReceiptEntity.serverId` (converted to
 *   `String` at the edge — see `ReceiptRepository` for why the entity holds a `String`
 *   when DummyJSON hands back an `Int`).
 */
@Serializable
data class CartResponse(
    val id: Int,
)
