package com.fetchclone.core.data.network.model

import kotlinx.serialization.Serializable

/** Paginated envelope returned by `GET /products`. */
@Serializable
data class ProductsResponse(
    val products: List<ProductDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)
