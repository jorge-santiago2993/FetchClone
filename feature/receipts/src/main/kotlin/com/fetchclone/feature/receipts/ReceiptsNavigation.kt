package com.fetchclone.feature.receipts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Navigation surface for the receipts flow.
 *
 * Mirrors `OffersNavigation`: the feature module owns its route and contributes its own
 * `NavGraphBuilder` entry, and `:app` only assembles them. That keeps the app's navigation
 * file from accumulating feature-specific Composable wiring, and means adding a screen to
 * this feature never touches `:app`.
 *
 * ## Why this feature exports one route and no callbacks
 *
 * `offersFeedScreen` takes an `onOfferClick` because the offers feature navigates to a
 * destination it does not own. This one takes nothing: there is no receipt detail screen
 * (explicitly out of scope), and the scan action is not navigation — it writes a row and
 * the list updates itself.
 *
 * A feature that needs no callbacks from `:app` is a feature with no outward coupling,
 * which is the ideal a modularised graph is aiming at. Worth noticing rather than
 * assuming: it is true here because the receipts screen is genuinely self-contained, not
 * because of anything clever in the wiring.
 *
 * Routes are plain strings to match `:feature:offers`. Type-safe navigation with
 * `@Serializable` route objects is the more modern option and would be the upgrade to make
 * across both features at once, rather than leaving the two inconsistent.
 */
object ReceiptsRoutes {
    const val LIST = "receipts"
}

/** The receipts list destination. */
fun NavGraphBuilder.receiptsScreen() {
    composable(route = ReceiptsRoutes.LIST) {
        ReceiptsScreen()
    }
}
