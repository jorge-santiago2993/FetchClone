package com.fetchclone.feature.offers

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fetchclone.core.data.model.Offer
import com.fetchclone.core.ui.component.NetworkImage
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One offer in the feed grid: image, price, title, brand · category, rating.
 *
 * Stateless — it takes an [Offer] and an [onClick]. The whole card is the touch target
 * (tapping opens the detail screen). The heart is decoration only; see [FavoriteButton].
 */
@Composable
internal fun OfferCard(
    offer: Offer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            Box {
                NetworkImage(
                    url = offer.thumbnailUrl,
                    contentDescription = offer.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                if (offer.discountPercentage >= 1.0) {
                    DiscountBadge(
                        percentage = offer.discountPercentage,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    )
                }
                FavoriteButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = formatUsd(offer.priceCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = offer.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = offer.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RatingLabel(rating = offer.rating)
            }
        }
    }
}

/** "Brand · Category" — or just the category when the product has no brand. */
private fun Offer.subtitle(): String {
    val prettyCategory = category.replace('-', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    return listOfNotNull(brand, prettyCategory.ifBlank { null }).joinToString(" · ")
}

@Composable
private fun DiscountBadge(percentage: Double, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "${percentage.roundToInt()}% OFF",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RatingLabel(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = RatingStarColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "%.1f".format(rating),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Decorative "favorite" heart.
 *
 * There is no favorites concept in the data model yet — wiring it up means an
 * `OfferEntity.isFavorite` column plus a toggle through the DAO/repository and a
 * callback out of this card. Until then it is rendered as a non-interactive accent so
 * the card matches the target design.
 */
@Composable
private fun FavoriteButton(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.28f), CircleShape)
            .padding(6.dp)
            .size(16.dp),
    )
}

/**
 * Skeleton shown in place of an [OfferCard] for a row that Room knows exists but hasn't
 * loaded into the paging window yet (i.e. `LazyPagingItems.get(index) == null` because
 * placeholders are enabled). Same footprint as a real card so the grid doesn't jump
 * when the row resolves.
 */
@Composable
internal fun OfferCardPlaceholder(modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkeletonBar(widthFraction = 0.4f)
                SkeletonBar(widthFraction = 0.9f)
                SkeletonBar(widthFraction = 0.6f)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            ),
    )
}

// NumberFormat is not thread-safe, but Compose only formats on the main/snapshot
// thread, so a single shared instance is fine and avoids re-allocating per recomposition.
private val usdFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

internal fun formatUsd(cents: Long): String = usdFormat.format(cents / 100.0)

private val RatingStarColor = Color(0xFFF2A600)

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

@Preview(name = "OfferCard · discounted", widthDp = 200)
@Composable
private fun OfferCardDiscountedPreview() {
    OffersThemedPreview {
        Box(Modifier.padding(12.dp)) {
            OfferCard(offer = OffersPreviewData.offers[3], onClick = {})
        }
    }
}

@Preview(name = "OfferCard · no discount, long title", widthDp = 200)
@Composable
private fun OfferCardPlainPreview() {
    OffersThemedPreview {
        Box(Modifier.padding(12.dp)) {
            OfferCard(offer = OffersPreviewData.offers[2], onClick = {})
        }
    }
}

@Preview(name = "OfferCard · dark", widthDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OfferCardDarkPreview() {
    OffersThemedPreview {
        Box(Modifier.padding(12.dp)) {
            OfferCard(offer = OffersPreviewData.offers[4], onClick = {})
        }
    }
}

@Preview(name = "OfferCard · no brand", widthDp = 200)
@Composable
private fun OfferCardNoBrandPreview() {
    OffersThemedPreview {
        Box(Modifier.padding(12.dp)) {
            OfferCard(offer = OffersPreviewData.offers[1], onClick = {})
        }
    }
}

@Preview(name = "OfferCard · placeholder skeleton", widthDp = 200)
@Composable
private fun OfferCardPlaceholderPreview() {
    OffersThemedPreview {
        Box(Modifier.padding(12.dp)) {
            OfferCardPlaceholder()
        }
    }
}
