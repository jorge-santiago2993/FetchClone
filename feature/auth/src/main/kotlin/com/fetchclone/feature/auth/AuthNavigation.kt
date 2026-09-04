package com.fetchclone.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.fetchclone.core.data.model.SignOutReason

/**
 * Navigation surface for the sign-in flow.
 *
 * Mirrors `OffersNavigation` and `ReceiptsNavigation`: the feature owns its route and its
 * `NavGraphBuilder` entry, and `:app` supplies the cross-feature inputs.
 *
 * ## Why this graph has exactly one destination and no exits
 *
 * There is no "back", no "continue as guest", and no route out of here. The way a user
 * leaves this screen is by signing in, which changes `AuthState`, which makes `:app`
 * replace this entire graph with the main one.
 *
 * That is why [signOutReason] arrives as a **parameter rather than a route argument**.
 * Encoding it in the path — `auth/login/{reason}` — would be the reflex, and it is wrong
 * here: the reason is a property of the *session*, which `:app` is already observing, not
 * a navigation argument someone chose to pass. Putting it in the route would create a
 * second copy of a fact that already has an owner, and the two could disagree — a stale
 * back-stack entry saying `SESSION_EXPIRED` after the user has deliberately signed out.
 *
 * Routes are plain strings, matching the rest of the project. Type-safe navigation
 * (`@Serializable` route objects) is the modern replacement and is noted as a follow-up
 * across all three feature modules rather than being adopted in one of them.
 */
object AuthRoutes {
    const val LOGIN = "auth/login"

    /** The account tab, inside the *authenticated* graph. */
    const val ACCOUNT = "auth/account"
}

/**
 * The login destination.
 *
 * @param signOutReason why the user is here; drives the prompt text.
 * @param showDemoShortcut whether to offer the DummyJSON demo credentials. `:app` passes
 *   the debug flag — the shortcut is a development convenience, not a product feature.
 */
fun NavGraphBuilder.loginScreen(
    signOutReason: SignOutReason,
    showDemoShortcut: Boolean,
) {
    composable(route = AuthRoutes.LOGIN) {
        LoginScreen(
            signOutReason = signOutReason,
            showDemoShortcut = showDemoShortcut,
        )
    }
}

/**
 * The account destination — profile and sign-out.
 *
 * Lives in this module rather than in `:app` because it is *about the session*, which is
 * what this feature owns. The alternative was a sign-out action in the receipts screen's
 * top bar, which would have handed `:feature:receipts` a reason to know the session exists.
 */
fun NavGraphBuilder.accountScreen() {
    composable(route = AuthRoutes.ACCOUNT) {
        AccountScreen()
    }
}
