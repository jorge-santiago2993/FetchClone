package com.fetchclone

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.fetchclone.feature.offers.OffersRoutes
import com.fetchclone.feature.offers.navigateToOfferDetail
import com.fetchclone.feature.offers.offerDetailScreen
import com.fetchclone.feature.offers.offersFeedScreen

/**
 * App root: owns the single [NavHost].
 *
 * Deliberately tiny. Each feature module contributes its own `NavGraphBuilder`
 * extensions ([offersFeedScreen], [offerDetailScreen]); this file only assembles them
 * and supplies the cross-feature navigation callbacks. Adding a feature is a couple of
 * lines here, not a growing pile of `composable { }` blocks.
 */
@Composable
fun FetchCloneApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = OffersRoutes.FEED,
    ) {
        offersFeedScreen(onOfferClick = navController::navigateToOfferDetail)
        offerDetailScreen(onBack = navController::navigateUp)
    }
}
