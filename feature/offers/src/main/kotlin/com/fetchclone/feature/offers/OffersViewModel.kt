package com.fetchclone.feature.offers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.data.repository.FeedRefreshState
import com.fetchclone.core.data.repository.OffersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the offers feed.
 *
 * ### Two outputs, on purpose
 *
 * 1. [offers] — the paged list, `Flow<PagingData<Offer>>`, handed to the Screen and
 *    collected with `collectAsLazyPagingItems()`. Collecting it is what drives the
 *    `RemoteMediator` (network → Room). Wrapped in [cachedIn] so the pages survive
 *    configuration changes and so multiple collectors (list + header) are safe.
 *
 * 2. [uiState] — the full-screen [OffersUiState], derived from *two* signals the
 *    repository exposes: the mediator's refresh status and the cached-offer count.
 *    Paging's own `LoadState` is intentionally not used for this (see [OffersUiState]).
 *
 * ### Why the constructor takes only the interface
 *
 * No Android types, no Paging infrastructure — just [OffersRepository]. That is what
 * makes `OffersViewModelTest` a plain JUnit test with a hand-written fake: no
 * Robolectric, no Hilt, no in-memory database.
 *
 * ### Why `stateIn` with `WhileSubscribed(5_000)`
 *
 * - `stateIn` turns the cold `combine` flow into a hot [StateFlow] with an initial
 *   value, so the Composable always has something to render on first composition and
 *   the derivation runs once and is shared, not per-collector.
 * - `WhileSubscribed(5_000)` keeps the upstream alive for 5s after the last collector
 *   leaves, so a rotation doesn't tear down and rebuild the flow (and re-hit Room).
 *   `Lazily` would leak the collection for the ViewModel's whole life; `Eagerly` would
 *   run it even when the screen is backgrounded. `WhileSubscribed` is the standard
 *   choice for UI state.
 *
 * ### Concurrency: why there is no `viewModelScope.launch { }` anywhere here
 *
 * This is a common point of confusion the first time you write a Paging 3 ViewModel,
 * so it is worth being explicit about it — this is exactly the kind of thing to be
 * ready to explain in an interview.
 *
 * **`viewModelScope.launch { }` is for two specific jobs: firing a one-shot `suspend`
 * call, or manually `collect`-ing a `Flow` to push values into a `MutableStateFlow`.**
 * Neither job exists in this ViewModel. `OffersRepository.offersFeed()`,
 * `.feedRefreshState`, and `.cachedOfferCount()` are all ordinary, **non-`suspend`**
 * members that return a `Flow`/`StateFlow` — a *description* of ongoing async work, not
 * its result. There is nothing to `launch`; there is only a pipeline to declare with
 * `combine`/`map`, and a scope to hand to the two operators that terminate it:
 * - [cachedIn] launches (internally) a coroutine in `viewModelScope` that multicasts
 *   `Pager.flow` to every collector and survives configuration changes.
 * - [stateIn] launches (internally) a coroutine in `viewModelScope` that collects
 *   `combine(...)` and republishes it as a hot `StateFlow`.
 *
 * So `viewModelScope` **is** in use, twice — just as a constructor argument to a
 * library operator instead of an explicit `launch` block. If this ViewModel ever grows
 * a one-shot action (e.g. a manual "retry" button that calls a `suspend fun refresh()`
 * on the repository rather than relying on Paging's own retry), *that* is where an
 * explicit `viewModelScope.launch { repository.refresh() }` would belong.
 *
 * ### Concurrency: is any of this running on the main thread?
 *
 * No — but not because this file dispatches anything itself. Every `suspend` call this
 * pipeline eventually makes is to a library that independently guarantees it is
 * "main-safe" (never blocks the calling thread, wherever that thread is):
 *
 * | Layer | Call | Off-main guarantee comes from |
 * |---|---|---|
 * | Room read | `offersDao.observeCount()` / `pagingSource()` | Room's own query executor — a `Flow` or suspend fun from a `@Dao` is documented to never block the caller |
 * | Room write | `offersDao.upsertOffers(...)`, `database.withTransaction { }` | Room's transaction executor — same guarantee, for writes |
 * | Network | `api.getProducts(...)` | Retrofit's suspend call adapter hands the request to OkHttp's own dispatcher thread pool |
 *
 * `OffersRemoteMediator.load()` (see that class) calls straight into Retrofit and
 * Room with no `withContext(Dispatchers.IO)` of its own — and needs none, precisely
 * because of the table above. Paging invokes `load()` through its own internal
 * coroutine machinery, but that detail doesn't matter here: the method's body never
 * does blocking work directly, it only awaits calls that already hop off-thread on
 * their own. **The RemoteMediator is the orchestrator of the network→DB write, not the
 * source of the thread-safety** — that credit belongs to Room and Retrofit being
 * main-safe libraries. This is also why `DefaultOffersRepository.offersFeed()` and
 * `cachedOfferCount()` are safe to call from anywhere, including directly in this
 * class's field initializers: building a `Pager` or returning a `Flow` reference does
 * no I/O by itself — the I/O only happens later, when something collects the flow.
 */
@HiltViewModel
class OffersViewModel @Inject constructor(
    repository: OffersRepository,
) : ViewModel() {

    val offers: Flow<PagingData<Offer>> =
        repository.offersFeed().cachedIn(viewModelScope)

    val uiState: StateFlow<OffersUiState> =
        combine(
            repository.feedRefreshState,
            repository.cachedOfferCount(),
        ) { refresh, cachedCount ->
            reduce(refresh, hasCache = cachedCount > 0)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            // Start on Loading, never on Empty: at subscription time the mediator may
            // not have reported yet (Idle) and the count may still be 0 — that is
            // "we haven't looked yet", not "there is nothing".
            initialValue = OffersUiState.Loading,
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Pure function — the whole screen-state decision in one place, which is
         * exactly what the unit test pins down. See the truth table in [OffersUiState].
         */
        fun reduce(refresh: FeedRefreshState, hasCache: Boolean): OffersUiState =
            when (refresh) {
                FeedRefreshState.Idle,
                FeedRefreshState.Loading,
                    -> if (hasCache) OffersUiState.Success else OffersUiState.Loading

                FeedRefreshState.Success ->
                    if (hasCache) OffersUiState.Success else OffersUiState.Empty

                is FeedRefreshState.Error ->
                    if (hasCache) OffersUiState.Success else OffersUiState.Error(refresh.message)
            }
    }
}
