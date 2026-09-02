package com.fetchclone.core.data.repository

import androidx.paging.PagingData
import com.fetchclone.core.data.model.Offer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The offers feature's data contract.
 *
 * ### Why an interface (Dependency Inversion)
 *
 * `:feature:offers` depends on this abstraction, not on `DefaultOffersRepository`. That
 * buys three things a senior engineer should name:
 * 1. **Testability** — the ViewModel test swaps in a hand-written fake with no Room,
 *    no Retrofit, no Paging infrastructure (see `OffersViewModelTest`).
 * 2. **A seam for change** — an in-memory or mock implementation for previews/demos,
 *    or a swap of the backing store, never touches the feature module.
 * 3. **A documented surface** — this file is the whole API the feature can use.
 *
 * Hilt binds the implementation via `@Binds` in `RepositoryModule`.
 *
 * ### Why three members instead of one
 *
 * Offline-first paging has two orthogonal facts the UI needs and Paging alone doesn't
 * give you cleanly from outside the Composable:
 * - the **list** ([offersFeed]) — always sourced from Room;
 * - the **refresh status** ([feedRefreshState]) — is the network first-load working?
 * - the **cache size** ([cachedOfferCount]) — do we have anything to show at all?
 *
 * The ViewModel combines the last two into its screen state. See [FeedRefreshState].
 */
interface OffersRepository {

    /**
     * The paged feed, backed entirely by the Room `offers` table.
     *
     * **Collecting this stream is what starts the network sync.** The `Pager` only runs
     * its `RemoteMediator` while something is actively collecting `PagingData`
     * (normally the Screen via `collectAsLazyPagingItems()`). If only
     * [feedRefreshState] / [cachedOfferCount] are observed, no fetch happens — by
     * design: no UI, no work.
     *
     * The returned flow is not cached here; the ViewModel is responsible for
     * `cachedIn(viewModelScope)` so the stream survives configuration changes.
     */
    fun offersFeed(): Flow<PagingData<Offer>>

    /** Status of the most recent first-page refresh performed by the mediator. */
    val feedRefreshState: StateFlow<FeedRefreshState>

    /** Number of offers currently in the Room cache; emits on every cache write. */
    fun cachedOfferCount(): Flow<Int>
}
