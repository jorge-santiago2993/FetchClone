package com.fetchclone.feature.offers

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.ui.theme.FetchCloneTheme

/**
 * Shared fixtures for `@Preview` functions in this module. Kept in one place so the
 * previews stay short and the sample data is consistent across components.
 *
 * `thumbnailUrl` is left blank on purpose — [com.fetchclone.core.ui.component.NetworkImage]
 * renders its placeholder in inspection mode regardless, so previews never need a real URL.
 */
internal object OffersPreviewData {

    val offers: List<Offer> = listOf(
        Offer(
            id = 1,
            title = "Essence Mascara Lash Princess",
            description = "Volumizing mascara for dramatic lashes.",
            category = "beauty",
            brand = "Essence",
            priceCents = 999,
            discountPercentage = 10.48,
            rating = 2.56,
            stock = 99,
            thumbnailUrl = "",
        ),
        Offer(
            id = 2,
            title = "Eyeshadow Palette with Mirror",
            description = "Twelve blendable everyday shades.",
            category = "beauty",
            brand = null,
            priceCents = 1999,
            discountPercentage = 18.19,
            rating = 2.86,
            stock = 34,
            thumbnailUrl = "",
        ),
        Offer(
            id = 3,
            title = "Powder Canister With A Deliberately Long Name To Exercise Two-Line Truncation",
            description = "Loose setting powder.",
            category = "beauty",
            brand = "Velvet Touch",
            priceCents = 1499,
            discountPercentage = 0.0,
            rating = 4.64,
            stock = 89,
            thumbnailUrl = "",
        ),
        Offer(
            id = 4,
            title = "Red Lipstick",
            description = "Matte, long-wear formula.",
            category = "beauty",
            brand = "Chic Cosmetics",
            priceCents = 1299,
            discountPercentage = 53.0,
            rating = 4.36,
            stock = 91,
            thumbnailUrl = "",
        ),
        Offer(
            id = 5,
            title = "Calvin Klein Slim Fit Jeans",
            description = "Dark-wash stretch denim.",
            category = "mens-shirts",
            brand = "Calvin Klein",
            priceCents = 4499,
            discountPercentage = 12.5,
            rating = 4.1,
            stock = 20,
            thumbnailUrl = "",
        ),
        Offer(
            id = 6,
            title = "Annibale Colombo Leather Sofa",
            description = "Hand-finished Italian leather.",
            category = "furniture",
            brand = "Annibale Colombo",
            priceCents = 250_000,
            discountPercentage = 15.0,
            rating = 4.9,
            stock = 3,
            thumbnailUrl = "",
        ),
    )

    val single: Offer get() = offers.first()
}

/**
 * Wraps preview content in the app theme + a themed surface so colors resolve.
 *
 * `dynamicColor = false` is important: Layoutlib renders `dynamicDarkColorScheme()` as
 * near-black, which makes dark previews look broken. Turning it off uses the static
 * light/dark schemes, which is also a truer preview of a device without Material You.
 */
@Composable
internal fun OffersThemedPreview(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FetchCloneTheme(dynamicColor = false) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
