package com.fetchclone.core.data.network

import com.fetchclone.core.data.network.model.CartRequest
import com.fetchclone.core.data.network.model.CartResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit binding for DummyJSON's cart endpoints, which stand in for receipt submission.
 *
 * ## Why these methods return the body directly and let failures throw
 *
 * Neither method returns `Response<T>` or a `Result`. A non-2xx makes Retrofit throw
 * `HttpException`, and a connectivity failure throws `IOException`. That looks less tidy
 * than a wrapper type, and it is the right shape here, because **the distinction the
 * outbox depends on is precisely the one those two exception types already draw**:
 *
 * | Thrown | Meaning | Outbox response |
 * |---|---|---|
 * | `HttpException`, code 4xx | the server understood and refused | terminal `REJECTED` |
 * | `HttpException`, code 5xx | the server broke | retry with backoff |
 * | `IOException` | we could not ask | retry with backoff |
 *
 * `ReceiptErrorClassifier` turns that into a decision in one place. Wrapping every call in
 * a `Result<T>` would flatten 4xx and 5xx into "failure" and push the classification back
 * out to call sites, which is exactly the conflation the spec warns against — "4xx is
 * terminal. 5xx and IO are retryable. Do not conflate them."
 *
 * `OffersRemoteMediator` already leans on the same two exception types for the same
 * reason, so this is the established convention in this codebase rather than a new one.
 */
interface CartsApi {

    /**
     * `POST /auth/carts/add` — submits a receipt's line items.
     *
     * ### The `Idempotency-Key` header is the load-bearing part of this signature
     *
     * [idempotencyKey] is the receipt's client-generated UUID primary key, passed
     * unchanged on every attempt for that receipt. It is what makes the retry loop safe:
     * if this call succeeds server-side but the response is lost to a dropped connection,
     * the retry carries the same key and a correct server returns the original result
     * instead of awarding the receipt twice.
     *
     * **The way to get this wrong is to generate the key per attempt** — a fresh
     * `UUID.randomUUID()` at the call site looks identical, compiles, and silently
     * disables the entire mechanism. Taking it as a parameter, sourced from a durable
     * primary key, makes that mistake hard: there is no key to invent here.
     *
     * DummyJSON ignores the header. It is sent anyway because the *client* half of the
     * contract is what this project is demonstrating, and because the header is what makes
     * `requeueStalledUploads()` a safe recovery rather than a double-award risk.
     *
     * ### On `Idempotency-Key` as a custom header
     *
     * It is not an IANA-registered header; it is a de-facto convention (Stripe's naming,
     * widely copied). A real integration would use whatever the backend documents, and the
     * `X-` prefix convention is deprecated by RFC 6648, so the unprefixed name is correct.
     */
    @POST("auth/carts/add")
    suspend fun addCart(
        @Header(HEADER_IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: CartRequest,
    ): CartResponse

    /**
     * `GET /auth/carts/{id}` — re-fetches a submitted receipt during reconciliation.
     *
     * **Expect this to 404 in this app, and treat that as "still processing".** DummyJSON
     * simulates writes without persisting them: `POST /carts/add` returns id 51 while only
     * carts 1-50 exist, so fetching the id it just handed us always misses. A real backend
     * would return the record with a resolution status.
     *
     * Mapping that 404 to an error would be the wrong call even against a real server.
     * "The record is not there yet" is a normal state in an asynchronous pipeline — write
     * visibility lags acknowledgement in any replicated store — and treating it as failure
     * would mark perfectly healthy receipts as broken. `RemoteReceiptProcessor` is where
     * that mapping lives.
     */
    @GET("auth/carts/{id}")
    suspend fun getCart(@Path("id") cartId: String): CartResponse

    companion object {
        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
