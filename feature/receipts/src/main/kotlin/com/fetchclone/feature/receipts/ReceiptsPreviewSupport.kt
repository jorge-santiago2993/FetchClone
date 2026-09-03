package com.fetchclone.feature.receipts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fetchclone.core.data.model.Receipt
import com.fetchclone.core.data.model.ReceiptLineItem
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.model.RejectReason
import com.fetchclone.core.ui.theme.FetchCloneTheme

/**
 * Fixtures for this module's `@Preview` functions, mirroring `OffersPreviewSupport`.
 *
 * The set below is chosen to cover **every branch of the status badge**, not to look like
 * a plausible list — a preview whose value is catching a broken state has to contain that
 * state. Between them, [ReceiptsPreviewData.all] renders queued, uploading, processing,
 * awarded, rejected, retrying and exhausted.
 */
internal object ReceiptsPreviewData {

    /** A fixed instant so previews are byte-identical between renders. */
    private const val BASE_TIME = 1_756_800_000_000L

    private val lipstick = ReceiptLineItem(
        productId = 4,
        title = "Red Lipstick",
        quantity = 2,
        unitPriceCents = 1299,
        // Above PointsCalculator's partner threshold: worth a bonus.
        discountPercentage = 53.0,
    )

    private val palette = ReceiptLineItem(
        productId = 2,
        title = "Eyeshadow Palette with Mirror",
        quantity = 1,
        unitPriceCents = 1999,
        discountPercentage = 18.19,
    )

    private val powder = ReceiptLineItem(
        productId = 3,
        title = "Powder Canister",
        quantity = 1,
        unitPriceCents = 1499,
        // Below the partner threshold: no bonus. Keeps the fixture honest about mixed baskets.
        discountPercentage = 0.0,
    )

    private val basket = listOf(lipstick, palette, powder)

    val queued = Receipt(
        id = "11111111-1111-1111-1111-111111111111",
        capturedAt = BASE_TIME,
        lineItems = basket,
        status = ReceiptStatus.Queued,
    )

    val uploading = queued.copy(
        id = "22222222-2222-2222-2222-222222222222",
        capturedAt = BASE_TIME - 60_000,
        status = ReceiptStatus.Uploading,
    )

    val processing = queued.copy(
        id = "33333333-3333-3333-3333-333333333333",
        capturedAt = BASE_TIME - 120_000,
        status = ReceiptStatus.Processing,
        serverId = "51",
    )

    /** 25 base + 10 for each of the two partner items — the calculator's real output. */
    val awarded = queued.copy(
        id = "44444444-4444-4444-4444-444444444444",
        capturedAt = BASE_TIME - 3_600_000,
        status = ReceiptStatus.Awarded(points = 45),
        serverId = "52",
    )

    val rejected = queued.copy(
        id = "55555555-5555-5555-5555-555555555555",
        capturedAt = BASE_TIME - 7_200_000,
        lineItems = listOf(lipstick),
        status = ReceiptStatus.Rejected(RejectReason.DUPLICATE),
        serverId = "53",
    )

    /** Below the attempt budget, so the badge says "Retrying". */
    val retrying = queued.copy(
        id = "66666666-6666-6666-6666-666666666666",
        capturedAt = BASE_TIME - 300_000,
        status = ReceiptStatus.Failed(attempts = 2, nextAttemptAt = BASE_TIME + 4_000),
    )

    /** At the budget, so the same type renders "Couldn't send". */
    val exhausted = queued.copy(
        id = "77777777-7777-7777-7777-777777777777",
        capturedAt = BASE_TIME - 86_400_000,
        lineItems = listOf(palette, powder),
        status = ReceiptStatus.Failed(
            attempts = ReceiptStatus.MAX_UPLOAD_ATTEMPTS,
            nextAttemptAt = BASE_TIME,
        ),
    )

    /** Newest first, matching `ReceiptDao.observeAll`'s ordering. */
    val all: List<Receipt> = listOf(queued, uploading, processing, retrying, awarded, rejected, exhausted)
}

/**
 * Wraps preview content in the app theme.
 *
 * `dynamicColor = false` for the same reason as `:feature:offers`: Layoutlib renders
 * `dynamicDarkColorScheme()` as near-black, which makes dark previews look broken.
 */
@Composable
internal fun ReceiptsThemedPreview(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FetchCloneTheme(dynamicColor = false) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
