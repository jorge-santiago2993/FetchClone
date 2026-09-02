package com.fetchclone.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.fetchclone.core.data.database.FetchCloneDatabase
import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.database.entity.OfferRemoteKeyEntity
import com.fetchclone.core.data.mapper.toEntity
import com.fetchclone.core.data.network.ProductsApi
import com.fetchclone.core.data.repository.FeedRefreshState
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException
import java.io.IOException

/**
 * Keeps the Room `offers` table in sync with `GET /products` as the user pages.
 *
 * ## The mental model (be ready to draw this)
 *
 * ```
 *            collectAsLazyPagingItems()
 *   Screen  ─────────────────────────►  Pager
 *                                         │  pagingSourceFactory = { offersDao.pagingSource() }
 *                                         ▼
 *                             ┌───────  Room  ◄─── writes pages ─── RemoteMediator ──► ProductsApi
 *                             │  (SINGLE SOURCE OF TRUTH)                                (network)
 *                             ▼
 *                    PagingData<OfferEntity> ──map──► PagingData<Offer> ──► UI
 * ```
 *
 * The UI is **only ever** fed from Room. When the user scrolls near the end, Paging
 * asks this mediator for the next page; the mediator fetches it from the network,
 * writes it to Room, and Room's auto-invalidation pushes the new rows back through the
 * `Pager`. Offline, the fetch fails but the already-cached rows keep showing.
 *
 * ## Why `RemoteMediator` and not a network `PagingSource`
 *
 * A `PagingSource` that hits the network directly is simpler, but then the DB is a
 * *secondary* copy and you have two code paths (online vs offline) that drift. With a
 * `RemoteMediator` + Room `PagingSource`, there is exactly one code path: read from
 * Room; the mediator's only job is to *fill* Room. This is the Google-recommended
 * shape for "network + database" paging and the one to be able to justify.
 *
 * ## Key ownership of the refresh state
 *
 * This class does not decide UI state. It just reports REFRESH progress into
 * [refreshState] (`Loading` → `Success`/`Error`). APPEND progress is *not* reported
 * here — the Screen reads that from `LazyPagingItems.loadState.append` and shows an
 * inline footer spinner / retry button. Mixing the two would make "the whole screen is
 * loading" and "the next page is loading" indistinguishable.
 *
 * @param refreshState the repository-owned state machine the mediator publishes into.
 *   Passed in (not constructed here) so the repository can expose it to the ViewModel.
 *
 * ### Concurrency: why [load] never calls `withContext(Dispatchers.IO)`
 *
 * `load()` makes two kinds of `suspend` calls — `api.getProducts(...)` (Retrofit) and
 * `database.withTransaction { ... }` / the DAOs inside it (Room) — and both are
 * independently documented as main-safe: Retrofit's suspend adapter hands the request
 * to OkHttp's own dispatcher pool, and Room's transaction/query executor does the same
 * for the database. Paging calls this method through its own internal coroutine
 * machinery, but that is irrelevant to correctness here: this method's body never
 * blocks whatever thread it happens to start on, because it never does the I/O itself —
 * it only awaits calls into libraries that already guarantee they won't. Wrapping the
 * body in `withContext(Dispatchers.IO)` here would be redundant, not incorrect — it is
 * a defensive style some teams still use, but it adds a dispatcher hop for no
 * behavioral change. See `OffersViewModel`'s doc for the full picture, including why
 * `viewModelScope.launch` never appears either.
 */
@OptIn(ExperimentalPagingApi::class)
internal class OffersRemoteMediator(
    private val api: ProductsApi,
    private val database: FetchCloneDatabase,
    private val refreshState: MutableStateFlow<FeedRefreshState>,
) : RemoteMediator<Int, OfferEntity>() {

    private val offersDao = database.offersDao()
    private val remoteKeysDao = database.offerRemoteKeysDao()

    /**
     * `LAUNCH_INITIAL_REFRESH`: every time the feed is opened we re-fetch page 0.
     *
     * Trade-off, stated plainly:
     * - **Pro:** the top of the feed is never stale; simple; no timestamp bookkeeping.
     * - **Con:** a network round-trip on every open even if the cache is seconds old.
     *
     * **Alternative considered and declined:** `SKIP_INITIAL_REFRESH` gated by a
     * "last refreshed at" timestamp (a TTL). It saves requests but needs a small
     * metadata table and a clock to test. For this feed, freshness-on-open is the
     * better default; the TTL is a clean follow-up if the API gets rate-limited.
     *
     * Note this does **not** wipe the cache eagerly — `clearAll()` runs only *after* a
     * successful network response inside [load], so an offline open keeps its data.
     */
    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, OfferEntity>,
    ): MediatorResult {
        // Step 1: translate the paging request into a `skip` offset for the API.
        val skip: Int = when (loadType) {
            LoadType.REFRESH -> 0

            // The feed only grows downward; there is never a page "before" the first.
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)

            LoadType.APPEND -> {
                val lastOffer = state.lastItemOrNull()
                // Nothing loaded yet -> let REFRESH populate the first page instead.
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                val keys = remoteKeysDao.remoteKeyByOfferId(lastOffer.id)
                // nextKey == null => the previous page was the last one.
                keys?.nextKey
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        if (loadType == LoadType.REFRESH) {
            refreshState.value = FeedRefreshState.Loading
        }

        return try {
            // Step 2: fetch. `state.config.pageSize` is the single source of truth for
            // page size (configured on the Pager) — don't hardcode it here.
            val limit = state.config.pageSize
            val response = api.getProducts(limit = limit, skip = skip)
            val products = response.products
            val endOfPaginationReached =
                products.isEmpty() || skip + products.size >= response.total

            // Step 3: commit the page + its keys in ONE transaction. Atomicity matters:
            // a crash between "wrote offers" and "wrote keys" would leave the mediator
            // unable to compute the next page. `withTransaction` also coalesces Room's
            // invalidation so the UI sees one consistent update, not two.
            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    // Replace, don't merge: the server is authoritative on page 0 and
                    // on ordering. This also resets `feedPosition` cleanly.
                    offersDao.clearAll()
                    remoteKeysDao.clearAll()
                }

                val entities = products.mapIndexed { index, product ->
                    product.toEntity(feedPosition = skip + index)
                }
                val prevKey = if (skip == 0) null else (skip - limit).coerceAtLeast(0)
                val nextKey = if (endOfPaginationReached) null else skip + limit

                offersDao.upsertOffers(entities)
                remoteKeysDao.upsertAll(
                    entities.map { OfferRemoteKeyEntity(it.id, prevKey = prevKey, nextKey = nextKey) },
                )
            }

            if (loadType == LoadType.REFRESH) {
                refreshState.value = FeedRefreshState.Success
            }
            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (e: IOException) {
            // No connectivity / socket timeout. Recoverable — Paging will retry on
            // demand. Cache is untouched (we never got past the fetch).
            reportRefreshFailure(loadType, NO_CONNECTION_MESSAGE)
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            // Non-2xx from the backend. Also recoverable via retry.
            reportRefreshFailure(loadType, "Couldn't load offers (HTTP ${e.code()}).")
            MediatorResult.Error(e)
        }
        // NOTE: we intentionally do not catch generic Exception/Throwable. A
        // JSON-parsing failure or a bug is a programming error — let it surface as a
        // Paging LoadState.Error (and crash in debug) rather than masquerading as a
        // transient network blip.
    }

    private fun reportRefreshFailure(loadType: LoadType, message: String) {
        if (loadType == LoadType.REFRESH) {
            refreshState.value = FeedRefreshState.Error(message)
        }
    }

    private companion object {
        const val NO_CONNECTION_MESSAGE =
            "No internet connection. Check your network and try again."
    }
}
