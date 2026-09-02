package com.fetchclone.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A product offering cached from `GET /products`. This table is the **single source of
 * truth** for the offers feed: the UI never renders a network response directly, it
 * renders rows from here (see `OffersRemoteMediator`).
 *
 * ### Design notes a senior engineer should be able to defend
 *
 * - **`id` is the natural primary key.** DummyJSON product ids are stable, so we reuse
 *   them. `@Upsert` on this key makes re-fetching a page idempotent — a product that
 *   appears again on a later refresh updates in place instead of duplicating.
 *
 * - **`feedPosition` exists only to preserve server order.** A paged list must have a
 *   deterministic `ORDER BY`. We cannot rely on insertion order, and ordering by `id`
 *   would reshuffle the feed if the backend returns products in a curated order. So the
 *   `RemoteMediator` stamps each row with `skip + indexInPage` as it writes pages, and
 *   the paging query does `ORDER BY feedPosition ASC`. Trade-off: `feedPosition` is
 *   only meaningful within one refresh cycle — `LoadType.REFRESH` clears the table
 *   first so positions never collide across cycles.
 *
 * - **Money is stored as `priceCents: Long`, not `Double`.** Floating-point money
 *   accumulates rounding error and compares incorrectly. Integer cents is the standard
 *   fix; the mapper converts `Double` dollars → cents once, at the edge.
 *
 * - **No `@ColumnInfo(index = true)`.** We only ever query this table via the paging
 *   `SELECT ... ORDER BY feedPosition` and a `COUNT(*)`. Adding indexes we don't query
 *   would just cost write throughput on every page insert. Revisit if we add filtering
 *   (e.g. by `category`).
 *
 * Schema changes here require bumping `FetchCloneDatabase.version`; the DB is
 * configured with `fallbackToDestructiveMigration`, so no hand-written migration is
 * needed for this prep project (a production app would write one).
 */
@Entity(tableName = "offers")
data class OfferEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val description: String,
    val category: String,
    val brand: String?,
    val priceCents: Long,
    val discountPercentage: Double,
    val rating: Double,
    val stock: Int,
    val thumbnailUrl: String,
    /** Index of this offer in the server-ordered feed (`skip + indexInPage`). */
    val feedPosition: Int,
)
