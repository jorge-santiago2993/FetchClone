package com.fetchclone.core.data.network.model

import kotlinx.serialization.Serializable

/**
 * A single product from https://dummyjson.com/products.
 *
 * Only the fields the app needs are declared; the shared [kotlinx.serialization.json.Json]
 * instance ignores everything else the endpoint returns.
 */
@Serializable
data class ProductDto(
    val id: Int,
    val title: String,
    val description: String = "",
    val category: String = "",
    val price: Double = 0.0,
    val discountPercentage: Double = 0.0,
    val rating: Double = 0.0,
    val stock: Int = 0,
    val brand: String? = null,
    val thumbnail: String = "",
    val images: List<String> = emptyList(),
)
