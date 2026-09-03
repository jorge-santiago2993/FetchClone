package com.fetchclone.feature.offers

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * Navigation surface for the offers flow.
 *
 * The feature module owns its routes and its `NavGraphBuilder` entries; `:app` just
 * calls [offersFeedScreen] / [offerDetailScreen] inside its `NavHost` and supplies the
 * cross-feature callbacks. That keeps the app's navigation file from growing a body of
 * feature-specific composable wiring, and keeps each feature's routes private-ish to it.
 *
 * Routes are plain strings here to match the rest of the project's scaffolding. Type-safe
 * navigation (`@Serializable` route classes) is the more modern option and would remove
 * the manual `navArgument` / string parsing below.
 */
object OffersRoutes {
    const val FEED = "offers/feed"

    // Keep the "{offerId}" placeholder in sync with [ARG_OFFER_ID].
    const val DETAIL = "offers/detail/{offerId}"

    fun detail(offerId: String): String = "offers/detail/$offerId"
}

internal const val ARG_OFFER_ID = "offerId"

/** Navigate to the placeholder detail destination for [offerId]. */
fun NavController.navigateToOfferDetail(offerId: Int) {
    navigate(OffersRoutes.detail(offerId.toString()))
}

/**
 * The offers feed destination.
 *
 * @param onOfferClick invoked with the tapped offer's id; `:app` wires this to
 *   [navigateToOfferDetail].
 */
fun NavGraphBuilder.offersFeedScreen(onOfferClick: (offerId: Int) -> Unit) {
    composable(route = OffersRoutes.FEED) {
        OffersFeedScreen(onOfferClick = onOfferClick)
    }
}

/** The (placeholder) offer detail destination. */
fun NavGraphBuilder.offerDetailScreen(onBack: () -> Unit) {
    composable(
        route = OffersRoutes.DETAIL,
        arguments = listOf(navArgument(ARG_OFFER_ID) { type = NavType.StringType }),
    ) { backStackEntry ->
        OfferDetailPlaceholderScreen(
            offerId = backStackEntry.arguments?.getString(ARG_OFFER_ID),
            onBack = onBack,
        )
    }
}
