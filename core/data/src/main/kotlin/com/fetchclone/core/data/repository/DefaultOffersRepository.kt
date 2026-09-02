package com.fetchclone.core.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.fetchclone.core.data.database.FetchCloneDatabase
import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.mapper.toDomain
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.data.network.ProductsApi
import com.fetchclone.core.data.paging.OffersRemoteMediator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [OffersRepository]: wires a Paging 3 [Pager] whose pages come from Room and
 * whose cache is filled by [OffersRemoteMediator].
 *
 * ### Why `@Singleton`
 *
 * The repository owns [_feedRefreshState]. That state must be shared between the
 * mediator (which writes it) and every ViewModel that reads it, and it must outlive a
 * single ViewModel so a rotation doesn't lose "the refresh failed". One instance for
 * the app process is exactly right. It is also cheap — the `Pager` itself is created
 * lazily per [offersFeed] call.
 *
 * ### Why the `Pager` is created inside [offersFeed], not stored as a field
 *
 * `Pager.flow` is documented as single-collector; handing the same instance to two
 * collectors throws. Creating a fresh `Pager` per call (the ViewModel then does
 * `cachedIn`, which multicasts safely) is the pattern from the official docs.
 *
 * ### Concurrency: why [offersFeed] and [cachedOfferCount] are plain, non-`suspend` calls
 *
 * Neither method does any I/O itself — [offersFeed] only *constructs* a `Pager`
 * (cheap, in-memory) and returns its `.flow`; [cachedOfferCount] just returns a `Flow`
 * reference from Room. The actual disk reads happen later, lazily, whenever something
 * collects those flows — and Room guarantees that collection is main-safe on its own.
 * See the concurrency section on `OffersViewModel` for the full thread-by-thread
 * breakdown of who guarantees what.
 */
@Singleton
@OptIn(ExperimentalPagingApi::class)
class DefaultOffersRepository @Inject constructor(
    private val api: ProductsApi,
    private val database: FetchCloneDatabase,
) : OffersRepository {

    private val offersDao = database.offersDao()

    private val _feedRefreshState = MutableStateFlow<FeedRefreshState>(FeedRefreshState.Idle)
    override val feedRefreshState: StateFlow<FeedRefreshState> = _feedRefreshState.asStateFlow()

    override fun offersFeed(): Flow<PagingData<Offer>> =
        // `androidx.paging.Pager` is NOT a UI component (nothing to do with ViewPager /
        // Compose HorizontalPager). It is a data-layer builder: given a PagingConfig +
        // a PagingSource factory + a RemoteMediator, its `.flow` property is a
        // Flow<PagingData<T>>. It holds no data itself — pages are pulled lazily from
        // the PagingSource as the UI scrolls. This method returns that Flow, not a Pager.
        Pager(
            config = PagingConfig(
                // Match the API's natural page size (ProductsApi.DEFAULT_PAGE_SIZE).
                // Everything else stays at its default on purpose:
                //
                //  - enablePlaceholders = true. This is LOAD-BEARING for a
                //    RemoteMediator setup, not a cosmetic choice. Every page the
                //    mediator writes to Room invalidates this Room `PagingSource`,
                //    which regenerates a fresh one. With placeholders ON, Room reports
                //    the full cached row count, so `LazyPagingItems.itemCount` and item
                //    positions stay stable across that invalidation and the diff is
                //    cheap. With placeholders OFF, each regenerated source reloads only
                //    `initialLoadSize` rows from the anchor, the presenter "loses" the
                //    appended items and re-requests them, the mediator re-fetches, and
                //    that invalidation loop becomes a recomposition storm that starves
                //    the first frame (symptom: black screen for a few seconds on
                //    launch). The cost is that `offers[i]` can be null while a row
                //    loads — the Screen renders a skeleton card for those.
                //
                //  - initialLoadSize = pageSize * 3 (the default). A larger first load
                //    fills the viewport + prefetch window in one shot instead of
                //    triggering an immediate APPEND cascade.
                pageSize = ProductsApi.DEFAULT_PAGE_SIZE,
            ),
            remoteMediator = OffersRemoteMediator(api, database, _feedRefreshState),
            pagingSourceFactory = { offersDao.pagingSource() },
        ).flow
            // Map at the edge so entities never leave :core:data. `PagingData.map`
            // transforms each item lazily, page by page, as it is consumed.
            .map { pagingData -> pagingData.map(OfferEntity::toDomain) }

    override fun cachedOfferCount(): Flow<Int> = offersDao.observeCount()
}
