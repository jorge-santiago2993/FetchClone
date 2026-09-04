package com.fetchclone.core.data.network

import com.fetchclone.core.data.network.model.ProductsResponse
import com.fetchclone.core.network.FetchCloneApi
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for the DummyJSON products endpoint.
 *
 * ## The `auth/` prefix is the entire diff this feature made to the offers feed
 *
 * DummyJSON exposes the same products under two paths: `products` is open, and
 * `auth/products` returns a byte-identical response but 401s without a bearer token. The
 * app moved to the second one, and **this string is the whole change** -- no new parameter,
 * no header, no error handling. `ProductsResponse`, `OffersRemoteMediator`, the Room
 * schema and `OffersViewModel` are all untouched.
 *
 * That is what the layering is for, and it is worth noticing rather than taking for
 * granted. The token is attached beneath this interface by an interceptor it has never
 * heard of, and a 401 is refreshed and replayed beneath it by an authenticator it has
 * never heard of. This file does not know authentication exists.
 *
 * ## Why gating the feed matters beyond realism
 *
 * Paging fires REFRESH and APPEND independently, so an expiring token here produces
 * genuinely **simultaneous** 401s on separate threads -- which is the exact condition
 * `TokenRefresher`'s single-flight lock exists for. Leaving the feed unauthenticated would
 * have meant that condition could only ever be reproduced in a test.
 */
interface ProductsApi {

    /**
     * `GET /auth/products?limit={limit}&skip={skip}`
     *
     * @param limit page size; DummyJSON caps this at 100 and treats 0 as "all".
     * @param skip number of items to skip, i.e. `page * limit`.
     */
    @GET("auth/products")
    suspend fun getProducts(
        @Query("limit") limit: Int = FetchCloneApi.DEFAULT_PAGE_SIZE,
        @Query("skip") skip: Int = 0,
    ): ProductsResponse
}
