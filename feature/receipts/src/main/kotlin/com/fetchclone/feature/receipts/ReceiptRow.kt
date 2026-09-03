package com.fetchclone.feature.receipts

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fetchclone.core.data.model.Receipt
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One receipt in the list: when it was scanned, what was on it, its total, and where it is
 * in the submission pipeline.
 *
 * Stateless and non-clickable — a receipt detail screen is explicitly out of scope, so the
 * row deliberately offers no affordance suggesting one exists. A `Card` with no `onClick`
 * is the honest rendering; adding a chevron that went nowhere would be worse than plain.
 *
 * ## Why the row shows item titles rather than a merchant
 *
 * A real receipt leads with where it was bought. This one cannot: the "scan" fabricates
 * line items from the offers feed, and there is no merchant to show. Rather than inventing
 * one, the row summarises the contents — which is genuine data the receipt actually holds.
 * Displaying a fake merchant would make the simulated half of the app harder to see, and
 * the whole point of `ReceiptProcessor`'s seam is to keep that boundary visible.
 */
@Composable
internal fun ReceiptRow(
    receipt: Receipt,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = formatUsd(receipt.totalCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = receipt.itemSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatCapturedAt(receipt.capturedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(10.dp))
            ReceiptStatusBadge(status = receipt.status)
        }
    }
}

/**
 * "Red Lipstick, Eyeshadow Palette +2 more" — enough to recognise the receipt without a
 * detail screen.
 *
 * Two titles then a count, rather than all of them: line items are 3-4 products with long
 * names, and a row that grows with its contents makes the list jumpy to scan.
 */
private fun Receipt.itemSummary(): String {
    val shown = lineItems.take(SUMMARY_ITEM_COUNT).joinToString(", ") { it.title }
    val remaining = lineItems.size - SUMMARY_ITEM_COUNT
    return if (remaining > 0) "$shown +$remaining more" else shown
}

private const val SUMMARY_ITEM_COUNT = 2

/**
 * Formats the capture time in the **device's** zone.
 *
 * `capturedAt` is epoch millis — an absolute instant with no zone, which is what should be
 * stored. The zone belongs to display: a receipt scanned at 9pm should read "9:00 PM" to
 * the person who scanned it. `ZoneId.systemDefault()` is read per call rather than cached,
 * because a device can change zone mid-session (travel, DST) and a cached zone would
 * quietly render stale times.
 *
 * This is the counterpart to `TimeModule` providing `Clock.systemUTC()`: zone-free when
 * stored, zone-aware when shown.
 */
private fun formatCapturedAt(epochMillis: Long): String =
    capturedAtFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

// DateTimeFormatter is immutable and thread-safe (unlike NumberFormat), so one shared
// instance is safe anywhere. Pattern is fixed rather than localised, matching the rest of
// this scaffolding-level UI; a shippable app uses a localised skeleton.
private val capturedAtFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

// NumberFormat is NOT thread-safe, but Compose formats only on the main/snapshot thread,
// so a single shared instance avoids re-allocating per recomposition. Same reasoning as
// :feature:offers' formatUsd.
private val usdFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

/**
 * Cents to "$12.99".
 *
 * The division to `Double` happens **only here, at the display boundary**, after all
 * arithmetic is done in integer cents. That ordering is the entire discipline: a `Double`
 * that exists for one formatting call cannot accumulate error, whereas one that exists in
 * the data model compounds it through every sum.
 */
private fun formatUsd(cents: Long): String = usdFormat.format(cents / 100.0)

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

@Preview(name = "Row · queued", widthDp = 380)
@Composable
private fun ReceiptRowQueuedPreview() {
    ReceiptsThemedPreview {
        Column(Modifier.padding(12.dp)) { ReceiptRow(ReceiptsPreviewData.queued) }
    }
}

@Preview(name = "Row · awarded", widthDp = 380)
@Composable
private fun ReceiptRowAwardedPreview() {
    ReceiptsThemedPreview {
        Column(Modifier.padding(12.dp)) { ReceiptRow(ReceiptsPreviewData.awarded) }
    }
}

@Preview(name = "Row · rejected", widthDp = 380)
@Composable
private fun ReceiptRowRejectedPreview() {
    ReceiptsThemedPreview {
        Column(Modifier.padding(12.dp)) { ReceiptRow(ReceiptsPreviewData.rejected) }
    }
}

@Preview(name = "Row · exhausted, dark", widthDp = 380, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReceiptRowExhaustedDarkPreview() {
    ReceiptsThemedPreview {
        Column(Modifier.padding(12.dp)) { ReceiptRow(ReceiptsPreviewData.exhausted) }
    }
}
