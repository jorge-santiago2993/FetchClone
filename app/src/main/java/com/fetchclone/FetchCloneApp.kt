package com.fetchclone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fetchclone.core.data.model.AuthState
import com.fetchclone.core.data.model.SignOutReason
import com.fetchclone.feature.auth.AuthRoutes
import com.fetchclone.feature.auth.accountScreen
import com.fetchclone.feature.auth.loginScreen
import com.fetchclone.feature.offers.OffersRoutes
import com.fetchclone.feature.offers.navigateToOfferDetail
import com.fetchclone.feature.offers.offerDetailScreen
import com.fetchclone.feature.offers.offersFeedScreen
import com.fetchclone.feature.receipts.ReceiptsRoutes
import com.fetchclone.feature.receipts.receiptsScreen

/**
 * App root. Chooses between the signed-out and signed-in worlds, and owns the bottom bar
 * for the second one.
 *
 * ## The single point where the session decides what the user sees
 *
 * This `when` is the *entire* mechanism. Nothing else in the app navigates in response to
 * a session change: not the login screen on success, not the account screen on sign-out,
 * not the token refresher when it gives up at three in the morning. They all write
 * `AuthState`, and this reads it.
 *
 * That is the payoff for modelling the session as observable state rather than as an
 * event, and it is worth being able to say why the alternative decays. With one-shot
 * "logged out" events you end up handling navigation in several places — a collector in
 * the Activity, a callback on the login screen, something defensive in a ViewModel — and
 * they can disagree. The classic symptom is login pushed *onto* the authenticated back
 * stack, so Back returns to a screen whose ViewModel has no session.
 *
 * ## Why the graphs are swapped rather than navigated between
 *
 * Each branch below builds a **different `NavHost`**, keyed by a different
 * `startDestination`. Signing out does not navigate to login; it destroys the graph that
 * contained the feed and builds one that contains only login. The authenticated back stack
 * cannot survive, because it no longer exists.
 *
 * The tempting alternative is one graph containing every destination plus a
 * `LaunchedEffect(authState) { navController.navigate(LOGIN) { popUpTo(0) } }`. It works
 * most of the time and races the rest: an imperative navigation fired from a state
 * collector competes with whatever navigation the user just triggered, and the ordering
 * depends on recomposition timing. Structure beats a race.
 */
@Composable
fun FetchCloneApp(viewModel: SessionViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()

    when (val state = authState) {
        // Restoring the session from disk. Deliberately NOT the login screen: showing a
        // sign-in form to an already-signed-in user for the two frames it takes to read an
        // encrypted file is the exact flash `AuthState.Unknown` exists to prevent, and on a
        // slow device it is long enough to start typing into.
        AuthState.Unknown -> SessionLoading()

        is AuthState.SignedOut -> SignedOutApp(reason = state.reason)

        is AuthState.Authenticated -> SignedInApp()
    }
}

@Composable
private fun SessionLoading() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * The signed-out world: one destination, no bottom bar, no way out except signing in.
 *
 * It still uses a `NavHost` with a single destination rather than calling `LoginScreen`
 * directly. That keeps the two branches structurally identical, so the sign-in flow can
 * grow a second screen — registration, a password reset — without this file changing
 * shape.
 */
@Composable
private fun SignedOutApp(reason: SignOutReason) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = AuthRoutes.LOGIN) {
        loginScreen(
            signOutReason = reason,
            // Debug builds only. The DummyJSON demo credentials are published in its own
            // documentation, so there is nothing to protect -- but a "fill in the password"
            // button is not something to ship, and BuildConfig is what keeps that decision
            // from depending on anyone remembering.
            showDemoShortcut = BuildConfig.DEBUG,
        )
    }
}

@Composable
private fun SignedInApp() {
    val navController = rememberNavController()
    val currentDestination = navController.currentDestination()

    Scaffold(
        bottomBar = {
            // Hidden on non-top-level destinations, so pushing the offer detail screen
            // gives it the full window. Deriving visibility from the current destination
            // rather than tracking a boolean means it can never disagree with the back
            // stack — including after a process-death restore, which a remembered flag
            // would get wrong.
            if (currentDestination.isTopLevel()) {
                FetchCloneBottomBar(navController, currentDestination)
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = OffersRoutes.FEED,
            // Only the BOTTOM inset is consumed here. Each screen has its own `Scaffold`
            // with its own top app bar, and those already apply the status-bar inset —
            // passing the full `contentPadding` down would apply it twice and leave a
            // visible gap under every top bar. The bottom bar is this file's to own, so
            // its height is this file's to reserve.
            modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            offersFeedScreen(onOfferClick = navController::navigateToOfferDetail)
            offerDetailScreen(onBack = navController::navigateUp)
            receiptsScreen()
            accountScreen()
        }
    }
}

@Composable
private fun FetchCloneBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                // `hierarchy` rather than `route ==`: it walks the destination's parent
                // graphs, so a tab stays selected for nested destinations within it. It is
                // the right comparison even though this graph is currently flat — writing
                // the equality version means the highlight silently breaks the first time
                // someone nests a screen.
                selected = currentDestination.isIn(destination),
                onClick = { navController.navigateToTopLevel(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

/**
 * The bottom bar's tabs.
 *
 * An enum, so the bar is generated by iterating rather than hand-written per tab — adding
 * the Account tab for this feature was one entry, not a copy-pasted `NavigationBarItem`.
 * Each entry references the route constant its own feature module exports, so `:app` never
 * hardcodes a route string.
 */
private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Offers(OffersRoutes.FEED, "Offers", Icons.Filled.ShoppingCart),
    Receipts(ReceiptsRoutes.LIST, "Receipts", Icons.Filled.List),
    Account(AuthRoutes.ACCOUNT, "Account", Icons.Filled.Person),
}

@Composable
private fun NavHostController.currentDestination(): NavDestination? {
    val backStackEntry by currentBackStackEntryAsState()
    return backStackEntry?.destination
}

private fun NavDestination?.isTopLevel(): Boolean =
    TopLevelDestination.entries.any { this.isIn(it) }

private fun NavDestination?.isIn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

/**
 * Switches tabs the way a bottom bar is supposed to.
 *
 * The three options are the canonical combination, and each fixes a specific bug you get
 * without it:
 *
 * - **`popUpTo(startDestination) { saveState = true }`** — without it, every tab tap pushes
 *   onto the back stack, so a user who tapped between tabs five times has to press Back
 *   five times to leave the app. `saveState` preserves each tab's scroll position and
 *   ViewModel state as it is popped.
 * - **`launchSingleTop`** — without it, re-tapping the tab you are already on pushes a
 *   duplicate copy of the same screen.
 * - **`restoreState`** — the counterpart to `saveState`. Without it the state is saved and
 *   then never read, so returning to a tab resets it to the top.
 *
 * `saveState`/`restoreState` are why the offers feed does not re-fetch and re-scroll every
 * time the user checks their receipts.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
