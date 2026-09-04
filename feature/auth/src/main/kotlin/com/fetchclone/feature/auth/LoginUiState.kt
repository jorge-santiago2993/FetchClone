package com.fetchclone.feature.auth

/**
 * Everything the login screen renders.
 *
 * ## Why this is one data class and not a sealed hierarchy
 *
 * Both other features in this app expose a sealed `UiState`, so the departure is worth
 * justifying rather than being an inconsistency.
 *
 * A sealed hierarchy is right when the states are **mutually exclusive** and carry
 * different data — `OffersUiState` is `Loading | Empty | Error | Success` because a screen
 * showing a grid is not simultaneously showing an error page. A form is not like that. The
 * username and password fields exist in every state, they must survive a failed submission,
 * and "submitting" is a flag that overlays the form rather than replacing it. Modelling
 * that as `Idle | Submitting | Error` forces every variant to carry the field values so it
 * can restore them, at which point the "states" are one type with a mode flag, written the
 * long way.
 *
 * The general rule: **sealed types model what the screen *is*; fields model what it
 * *has*.** A form always has field values, so they are fields.
 *
 * [error] is the one thing that could reasonably have been a separate state, and is not,
 * for the same reason `ReceiptsUiState.userMessage` is a field — an error here decorates
 * the form (a message under the button, fields still editable, text still there) instead
 * of replacing it. A user who mistyped a password should not have to retype their username.
 */
data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
) {
    /**
     * Whether the sign-in button does anything.
     *
     * Deliberately a *derived* property rather than stored state. A stored `canSubmit`
     * would be a second source of truth for a fact already implied by three other fields,
     * and the bug that follows — the button enabled while a submission is in flight,
     * because someone updated `isSubmitting` and forgot the flag — is the classic one.
     *
     * The emptiness checks are the only client-side validation here. Note what is *not*
     * validated: password length, character classes, username format. The server owns
     * those rules, and a client that duplicates them either drifts out of step or blocks a
     * legitimate credential it merely disapproves of. Refusing to send an obviously empty
     * form is a courtesy; guessing the password policy is overreach.
     */
    val canSubmit: Boolean
        get() = !isSubmitting && username.isNotBlank() && password.isNotBlank()
}

/**
 * Why a sign-in attempt did not work.
 *
 * The two cases need genuinely different messages, and getting that wrong is a small but
 * real cruelty: telling an offline user their password is wrong sends them to a password
 * reset they do not need — one that will also fail, because it needs the network too.
 *
 * An enum rather than a `String`, following the same rule as `UserMessage` in
 * `:feature:receipts`: the data layer names the situation, the Composable owns the
 * sentence. It keeps copy out of unit-test assertions and leaves the text translatable.
 */
enum class LoginError {

    /** The server understood and refused. Retyping is the fix. */
    INVALID_CREDENTIALS,

    /** We never got an answer. Trying again later is the fix. */
    UNAVAILABLE,
}
