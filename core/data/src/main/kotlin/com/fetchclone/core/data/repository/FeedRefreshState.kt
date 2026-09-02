package com.fetchclone.core.data.repository

/**
 * Status of the most recent **REFRESH** (first page / pull-to-refresh) performed by
 * `OffersRemoteMediator`.
 *
 * ### Why this exists separately from Paging's own `LoadState`
 *
 * Paging 3 already exposes `CombinedLoadStates` (refresh / append / prepend), but you
 * can only observe it from a `LazyPagingItems` in the UI, *after* collecting the
 * `PagingData` stream. The ViewModel needs the refresh status **before** and
 * **independently of** the list — to decide between a full-screen spinner, an empty
 * state, and an error screen — and it needs it combined with "how many offers are
 * cached" (a DB fact, not a paging fact).
 *
 * The mediator publishes into this small state machine; the repository exposes it; the
 * ViewModel maps it (plus the cached count) to its `OffersUiState`. This keeps the
 * "which full-screen state am I in?" decision in one testable place and keeps the
 * mediator's `MediatorResult` return value purely about paging.
 *
 * **Alternative considered and declined:** expose only `Flow<PagingData<Offer>>` and
 * derive everything in the Composable from `lazyPagingItems.loadState`. That is the
 * most idiomatic Paging setup and is less code, but it pushes screen-state logic into
 * the UI where it is harder to unit-test, and it cannot represent "refresh failed but
 * we still have cache, so keep showing the list" without extra plumbing anyway.
 */
sealed interface FeedRefreshState {

    /** No refresh has started yet (or the mediator was told to skip the initial one). */
    data object Idle : FeedRefreshState

    /** A network refresh of the first page is in flight. */
    data object Loading : FeedRefreshState

    /** The last refresh completed and the cache now mirrors the server. */
    data object Success : FeedRefreshState

    /**
     * The last refresh failed. [message] is a user-facing string built in the data
     * layer (where the HTTP/IO exception types live) so the ViewModel doesn't need to
     * know about Retrofit.
     */
    data class Error(val message: String) : FeedRefreshState
}
