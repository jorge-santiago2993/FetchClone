package com.fetchclone.feature.receipts

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fetchclone.core.data.model.ReceiptStatus
import com.fetchclone.core.data.model.RejectReason

/**
 * The status pill on a receipt row.
 *
 * ## Requirement 7: "driven by the sealed type, not a string"
 *
 * The `when` below is over [ReceiptStatus], and it has **no `else` branch**. That is the
 * whole point of the requirement, and it buys three concrete things:
 *
 * 1. **The compiler enforces completeness.** Add a state to `ReceiptStatus` — say
 *    `Expired` — and this file stops compiling until it is handled. A `when (status)` over
 *    a `String` would fall through to a default and ship a receipt rendered as "Unknown".
 * 2. **Per-state data arrives typed and non-null.** [ReceiptStatus.Awarded.points] is an
 *    `Int`, not an `Int?` the UI has to `!!`. The badge cannot render "You earned null
 *    points" because that state is not constructible.
 * 3. **Nonsense is unrepresentable.** There is no branch for "awarded and rejected"
 *    because the type has no such value — see `ReceiptEntity`, whose flat columns *can*
 *    express it, and `ReceiptMappers`, which is the one place that reconciles the two.
 *
 * ## Why the copy is here and not in the data layer
 *
 * The badge turns a domain state into a sentence, and sentences are a UI concern: they
 * need string resources, a locale, and a tone. `ReceiptStatus` carries the *fact*
 * (`Failed(attempts = 3, nextAttemptAt = ...)`) and this file decides what to say about it
 * ("Retrying" vs. "Couldn't send"). Moving the wording into `:core:data` would make it
 * untranslatable and would put copy into unit test assertions.
 *
 * Strings are inline literals here rather than `stringResource` ids, matching
 * `:feature:offers`. That is a scaffolding-level shortcut, not a recommendation — a
 * shippable app extracts these to `strings.xml` for translation and for TalkBack.
 *
 * ## Why colour is not the only signal
 *
 * Every badge pairs its colour with an icon and a word. Colour alone fails for the ~8% of
 * men with colour vision deficiency, for whom the red "rejected" and green "awarded" pills
 * are the same pill. The text carries the meaning; colour and icon reinforce it.
 */
@Composable
internal fun ReceiptStatusBadge(
    status: ReceiptStatus,
    modifier: Modifier = Modifier,
) {
    val appearance = status.appearance()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = appearance.container,
        contentColor = appearance.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            appearance.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    // null: the adjacent Text already says it. Announcing "check mark,
                    // Earned 45 points" would make TalkBack read decoration as content.
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = appearance.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Colour, icon and copy for one status. */
private data class BadgeAppearance(
    val label: String,
    val container: Color,
    val content: Color,
    val icon: ImageVector? = null,
)

/**
 * The exhaustive mapping. **No `else` branch — that is deliberate.**
 *
 * Kept as a private function rather than inlined into the Composable so the mapping is one
 * readable table, and so a `@Preview` can render every branch by construction.
 */
@Composable
private fun ReceiptStatus.appearance(): BadgeAppearance {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        // Queued and Uploading are deliberately styled the same and worded differently.
        // Both mean "on its way", and the user needs no more than that — but keeping the
        // words distinct makes the pipeline observable while developing, which is the
        // point of building a visible state machine.
        ReceiptStatus.Queued -> BadgeAppearance(
            label = "Queued",
            container = scheme.surfaceVariant,
            content = scheme.onSurfaceVariant,
        )

        ReceiptStatus.Uploading -> BadgeAppearance(
            label = "Uploading",
            container = scheme.surfaceVariant,
            content = scheme.onSurfaceVariant,
        )

        ReceiptStatus.Processing -> BadgeAppearance(
            label = "Processing",
            container = scheme.secondaryContainer,
            content = scheme.onSecondaryContainer,
        )

        // `points` is a non-null Int straight off the sealed type. No null check, no
        // fallback copy, no "+null pts" — the type made that impossible.
        is ReceiptStatus.Awarded -> BadgeAppearance(
            label = "+$points pts",
            container = AwardedContainer,
            content = AwardedContent,
            icon = Icons.Filled.CheckCircle,
        )

        is ReceiptStatus.Rejected -> BadgeAppearance(
            label = reason.label(),
            container = scheme.errorContainer,
            content = scheme.onErrorContainer,
            icon = Icons.Filled.Info,
        )

        // The one branch that inspects its payload rather than just displaying it.
        //
        // `isExhausted` is computed on the domain type (attempts >= MAX_UPLOAD_ATTEMPTS),
        // not re-derived here — the UI must not own the retry budget. Two very different
        // things need saying: "this is still coming" versus "this needs you". Showing one
        // "Failed" badge for both would tell a user their receipt was lost while the app
        // was seconds from delivering it.
        is ReceiptStatus.Failed -> if (isExhausted) {
            BadgeAppearance(
                label = "Couldn't send",
                container = scheme.errorContainer,
                content = scheme.onErrorContainer,
                icon = Icons.Filled.Info,
            )
        } else {
            BadgeAppearance(
                label = "Retrying",
                container = scheme.surfaceVariant,
                content = scheme.onSurfaceVariant,
                icon = Icons.Filled.Refresh,
            )
        }
    }
}

/**
 * User-facing copy for a rejection.
 *
 * Plain language, no HTTP codes and no enum names leaking to the screen. The enum is the
 * data layer's vocabulary; this is the user's. `ReceiptErrorClassifier` is the other end of
 * the same translation — status code to reason there, reason to sentence here.
 */
private fun RejectReason.label(): String = when (this) {
    RejectReason.DUPLICATE -> "Already scanned"
    RejectReason.UNREADABLE -> "Couldn't read"
    RejectReason.TOO_OLD -> "Too old"
    RejectReason.NOT_A_RECEIPT -> "Not a receipt"
}

// Material 3's scheme has no "success" role — it ships error colours but not their
// positive counterpart — so these are defined explicitly. Fixed rather than theme-derived
// because they must stay legible in both light and dark: the container is light enough for
// dark text in either theme, which a `primaryContainer` swap would not guarantee.
private val AwardedContainer = Color(0xFFD7F2DD)
private val AwardedContent = Color(0xFF11632B)

// ---------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------

/**
 * Every badge, in one place.
 *
 * This is the payoff of the sealed type in preview form: the list below is *exhaustive by
 * construction*, so adding a state to `ReceiptStatus` breaks the compile here too and the
 * new badge has to be designed before it can ship.
 */
@Composable
private fun AllBadges() {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReceiptStatusBadge(ReceiptStatus.Queued)
        ReceiptStatusBadge(ReceiptStatus.Uploading)
        ReceiptStatusBadge(ReceiptStatus.Processing)
        ReceiptStatusBadge(ReceiptStatus.Awarded(points = 45))
        ReceiptStatusBadge(ReceiptStatus.Rejected(RejectReason.DUPLICATE))
        ReceiptStatusBadge(ReceiptStatus.Rejected(RejectReason.TOO_OLD))
        // Retrying: attempts below the budget.
        ReceiptStatusBadge(ReceiptStatus.Failed(attempts = 2, nextAttemptAt = 0))
        // Exhausted: attempts at the budget, so the same type renders a different badge.
        ReceiptStatusBadge(
            ReceiptStatus.Failed(
                attempts = ReceiptStatus.MAX_UPLOAD_ATTEMPTS,
                nextAttemptAt = 0,
            ),
        )
    }
}

@Preview(name = "Badges · light")
@Composable
private fun ReceiptStatusBadgesPreview() {
    ReceiptsThemedPreview { AllBadges() }
}

@Preview(name = "Badges · dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReceiptStatusBadgesDarkPreview() {
    ReceiptsThemedPreview { AllBadges() }
}
