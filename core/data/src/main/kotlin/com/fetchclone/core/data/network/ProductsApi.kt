package com.fetchclone.core.data.network

import com.fetchclone.core.data.network.model.ProductsResponse
import retrofit2.http.GET
import retrofit2.http.Query

/** Retrofit binding for the DummyJSON products endpoint. No auth required. */
interface ProductsApi {

    /**
     * `GET /products?limit={limit}&skip={skip}`
     *
     * @param limit page size; DummyJSON caps this at 100 and treats 0 as "all".
     * @param skip number of items to skip, i.e. `page * limit`.
     */
    @GET("products")
    suspend fun getProducts(
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
        @Query("skip") skip: Int = 0,
    ): ProductsResponse

    companion object {
        const val BASE_URL = "https://dummyjson.com/"
        const val DEFAULT_PAGE_SIZE = 20
    }
}
