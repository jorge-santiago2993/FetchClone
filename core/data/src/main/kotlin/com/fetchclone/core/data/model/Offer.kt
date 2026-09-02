package com.fetchclone.core.data.model

/**
 * A single product offering as the **feature layer** sees it.
 *
 * ### Why a dedicated domain model (and not just reuse [ProductDto] or [OfferEntity])
 *
 * The three-model split — network DTO → Room entity → domain model — is a deliberate
 * senior-level choice, not ceremony:
 *
 * - **Decoupling.** `ProductDto` is shaped by DummyJSON's JSON contract and
 *   `OfferEntity` is shaped by the SQLite schema (e.g. price stored as an integer
 *   number of cents, a `feedPosition` column that only exists to support paging).
 *   Neither shape should leak into UI code. If the backend renames a field or we add
 *   an index, only the mapper changes — the ViewModel and Composables are untouched.
 * - **The feature module never depends on Retrofit or Room.** `:feature:offers` only
 *   sees `Offer`. That keeps the module graph honest and compile times down, and makes
 *   the ViewModel trivially unit-testable with a fake repository.
 * - **Room is the single source of truth.** The UI is fed exclusively from
 *   `OfferEntity` rows (mapped to `Offer`); DTOs are an implementation detail of how
 *   the cache gets populated. See `OffersRemoteMediator`.
 *
 * **Alternative considered and declined:** a separate `:core:model` (or `:core:domain`)
 * module holding this class. It is cleaner in a large codebase, but for a single
 * feature it adds a module to wire for no real gain. `Offer` lives in `:core:data` and
 * is promoted to its own module the day a second feature needs it.
 *
 * The class is immutable (`val` only) so it is safe to publish across threads and to
 * use as Compose state without defensive copying.
 *
 * @property priceCents list price in **cents** — integer money avoids the rounding
 *   errors of `Double` arithmetic. Formatting to a currency string is a UI concern.
 * @property discountPercentage promo discount as a percentage (e.g. `12.5` = 12.5% off).
 *   This is what makes a product an "offer" in this feed; the UI renders it as the
 *   headline savings.
 */
data class Offer(
    val id: Int,
    val title: String,
    val description: String,
    val category: String,
    val brand: String?,
    val priceCents: Long,
    val discountPercentage: Double,
    val rating: Double,
    val stock: Int,
    val thumbnailUrl: String,
)
