package com.fetchclone.feature.receipts

import com.fetchclone.core.data.model.Receipt

/**
 * The full-screen state of the receipts list.
 *
 * ## Why this is much smaller than `OffersUiState`
 *
 * The offers feed needs `Loading`, `Empty`, `Error` and `Success`, derived by combining a
 * network refresh status with a cached count. This screen needs three states and no
 * combination — and the *reason* is the whole point of the architecture, so it is worth
 * stating plainly.
 *
 * **There is no `Error` state, because there is no operation that can fail in a way the
 * screen has to report.** The offers feed can fail to load, because its content lives on a
 * server. This screen's content is local: reading it is a Room query, and submitting is a
 * Room insert. Network failures still happen constantly — they just are not *this
 * screen's* problem. They are recorded on the individual receipt as
 * `ReceiptStatus.Failed`, shown as a badge on that row, and retried by the outbox. A
 * failed upload must never blank out a list of receipts that are all fine.
 *
 * That is the offline-first payoff expressed as a type: the error surface moved from the
 * screen to the row, because delivery stopped being something the user waits for.
 *
 * ### The one thing that *can* go wrong is not an error either
 *
 * The scan button needs a warm offers cache. When it is empty, the user gets a transient
 * message ([ReceiptsUiState.userMessage]) rather than a state change — because nothing
 * about the *list* has changed, and swapping the screen for an error page would be a wild
 * over-reaction to "try again in a second". That is also why it is a one-shot message and
 * not a persistent state: it describes a moment, not a condition.
 */
sealed interface ReceiptsUiState {

    /**
     * A transient, one-shot message to show over the current content — a snackbar.
     *
     * Present on every state because it is orthogonal to them: a message can arrive while
     * the list is showing or while it is empty, and it never changes which of those is on
     * screen.
     *
     * ### Why this is a nullable field and not a fourth state
     *
     * A `ReceiptsUiState.Message` state would have to *replace* the list to be shown, which
     * is precisely wrong for a snackbar — the user must keep seeing their receipts while
     * being told the cache is cold. Modelling a transient overlay as a state is a common
     * mistake; states are mutually exclusive, and this is not.
     *
     * The consumer must call `ReceiptsViewModel.onUserMessageShown` once displayed, or the
     * message reappears on every configuration change. That handshake is the price of
     * modelling an event in a state holder; the alternative — a `Channel` of one-shot
     * events — drops messages if the screen is not collecting, which is worse.
     */
    val userMessage: UserMessage?

    /** The first Room emission has not arrived yet. Sub-frame in practice; see below. */
    data class Loading(override val userMessage: UserMessage? = null) : ReceiptsUiState

    /** No receipts have ever been submitted — prompt the user to scan one. */
    data class Empty(override val userMessage: UserMessage? = null) : ReceiptsUiState

    /**
     * There are receipts to show.
     *
     * ### Why this one carries its payload, when `OffersUiState.Success` does not
     *
     * `OffersUiState.Success` is a bare marker because Paging owns the offers list as a
     * separate stream — the ViewModel never holds it. Here the list is a plain
     * `List<Receipt>` from a Room `Flow`, small and fully in memory, so the state can carry
     * it directly. That makes the ViewModel test able to assert on actual content rather
     * than just on which branch was taken.
     *
     * Paging would be the wrong tool for this screen anyway: receipts are a bounded set
     * owned entirely by this device, with no remote pages to fetch.
     */
    data class Success(
        val receipts: List<Receipt>,
        override val userMessage: UserMessage? = null,
    ) : ReceiptsUiState
}

/**
 * Something to tell the user, once.
 *
 * ### Why an enum rather than a `String`
 *
 * The data layer has no business writing user-facing copy, and `:core:data` has no access
 * to string resources. Passing a `String` up would also make the message untranslatable
 * and would put wording in a unit test's assertions, so changing a comma would break a
 * test. The enum names the *situation*; the Composable owns the sentence.
 *
 * Note this is the opposite call from `FeedRefreshState.Error`, which does carry a
 * message. That is defensible there because the text is built from HTTP details the UI
 * cannot see. Here the situation is fully known to the ViewModel, so there is nothing to
 * carry.
 */
enum class UserMessage {

    /**
     * The scan button was pressed with an empty offers cache.
     *
     * An artefact of simulating capture — a real camera has no such precondition. See
     * `ReceiptScanner`.
     */
    NO_OFFERS_TO_SCAN,
}
