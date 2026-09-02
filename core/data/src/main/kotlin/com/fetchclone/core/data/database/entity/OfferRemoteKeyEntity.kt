package com.fetchclone.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pagination bookkeeping for [OfferEntity], one row per cached offer.
 *
 * ### Why a separate "remote keys" table is the canonical `RemoteMediator` pattern
 *
 * A [androidx.paging.RemoteMediator] is told *"load the page after what the user is
 * currently looking at"* — it is handed a `PagingState` of already-loaded items, not a
 * page number. It must therefore derive the next network key from those items. Storing
 * the key **alongside the data it came from** (here: the `skip` offset that produced
 * each offer) is how we answer "what do I fetch next?" after a process death or an
 * offline start, when all we have is the Room cache.
 *
 * - `nextKey == null` means we have reached the end of the feed (no more pages).
 * - `prevKey == null` means this is the first page. We never PREPEND from the network
 *   in this feed (the list only grows downward), so `prevKey` is stored for
 *   completeness/debuggability but not acted on.
 *
 * **Keys are `skip` offsets, not page indices**, because DummyJSON's API is
 * `?limit=&skip=` — keeping the key in the API's own units removes a conversion and a
 * class of off-by-`pageSize` bugs.
 *
 * **Alternative considered and declined:** a single-row "next skip" table instead of
 * one row per item. It is simpler but breaks as soon as loads can happen at both ends
 * or out of order; the per-item table is the pattern every Paging codelab and the
 * Now-in-Android sample use, so it is the one to be able to explain.
 */
@Entity(tableName = "offer_remote_keys")
data class OfferRemoteKeyEntity(
    @PrimaryKey val offerId: Int,
    val prevKey: Int?,
    val nextKey: Int?,
)
