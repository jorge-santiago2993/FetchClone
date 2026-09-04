package com.fetchclone.core.network

/**
 * Where the app points, and the page size it asks for.
 *
 * These moved out of `ProductsApi` when `:core:network` was created. The base URL is a
 * property of *the connection*, not of any one endpoint — Retrofit is built from it long
 * before a service interface exists — so a service constant was always the wrong home for
 * it. With two services and, shortly, a third for auth, keeping it there would have meant
 * `CartsApi` and `AuthApi` reaching into `ProductsApi` for an address.
 *
 * [HOST] exists separately from [BASE_URL] because `AuthInterceptor` has to answer a
 * narrower question — *"is this request going to our own backend?"* — before attaching a
 * bearer token. Comparing hosts is the right check there; comparing URL prefixes would
 * also match a path on a different origin.
 */
object FetchCloneApi {

    const val HOST = "dummyjson.com"

    const val BASE_URL = "https://$HOST/"

    /**
     * DummyJSON caps `limit` at 100 and treats 0 as "all". 20 is a screen and a bit of
     * the offers grid, which is the size Paging's prefetch is tuned around.
     */
    const val DEFAULT_PAGE_SIZE = 20
}
