package com.fetchclone.feature.offers

/**
 * The **full-screen** state of the offers feed.
 *
 * ### Scope: what this models and what it deliberately does NOT
 *
 * This sealed hierarchy arbitrates the one-of-N screen swap: spinner vs. empty message
 * vs. error screen vs. the list. It does **not** model *within-list* paging states —
 * the "loading more…" footer and the "tap to retry" footer come from
 * `LazyPagingItems.loadState.append` in the Composable. Keeping the two separate is
 * the key design decision here: a failed *append* must not blow away a list the user
 * is already reading, and a successful list with a background refresh error should
 * still render.
 *
 * ### Why a `sealed interface` and not an enum or a data class with flags
 *
 * - Each state carries different data (`Error` has a message, the others don't). An
 *   enum can't; a `data class OffersUiState(isLoading, isError, error, isEmpty)` can
 *   represent nonsense like `isLoading && isError`.
 * - `sealed` gives the ViewModel and the Composable an **exhaustive `when`** with no
 *   `else` branch — add a state later and the compiler lists every place to handle it.
 * - `data object` for the stateless cases: cheap singletons with sensible
 *   `equals`/`toString`, which also makes ViewModel tests read naturally
 *   (`assertEquals(OffersUiState.Loading, state)`).
 *
 * ### How the ViewModel derives these (see [OffersViewModel])
 *
 * | RemoteMediator refresh | offers cached? | state     |
 * |------------------------|----------------|-----------|
 * | Idle / Loading         | no             | [Loading] |
 * | Idle / Loading         | yes            | [Success] |
 * | Success                | no             | [Empty]   |
 * | Success                | yes            | [Success] |
 * | Error                  | no             | [Error]   |
 * | Error                  | yes            | [Success] |  ← stale cache beats a blank error screen
 */
sealed interface OffersUiState {

    /** First load in progress and nothing cached yet — show a full-screen spinner. */
    data object Loading : OffersUiState

    /** Load succeeded but the feed is genuinely empty — show an empty-state message. */
    data object Empty : OffersUiState

    /**
     * First load failed and there is nothing cached to fall back to — show a
     * full-screen error with a retry action.
     *
     * @property message user-facing text supplied by the data layer.
     */
    data class Error(val message: String) : OffersUiState

    /**
     * There are offers to show — render the list, not a placeholder screen.
     *
     * It carries no payload on purpose. With Paging 3 the list is a *stream*
     * (`OffersViewModel.offers`, consumed by `collectAsLazyPagingItems()`), not a
     * value the ViewModel can hold — Paging owns the list's incremental loading and
     * diffing. So this state is just the "gate" (show list vs. show placeholder); the
     * rows come through the separate stream. The alternative — deriving loading/empty/
     * error straight from `LazyPagingItems.loadState` in the Composable and dropping
     * this class — is also valid; we keep the ViewModel state for unit-testability.
     */
    data object Success : OffersUiState
}
