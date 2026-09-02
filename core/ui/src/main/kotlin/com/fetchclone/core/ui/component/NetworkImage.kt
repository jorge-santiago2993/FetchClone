package com.fetchclone.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fetchclone.core.ui.theme.FetchCloneTheme

/**
 * App-wide remote image.
 *
 * Wrapping Coil here (instead of every feature calling `AsyncImage` directly) buys two
 * things: features don't take a dependency on Coil, and every remote image in the app
 * shares the same defaults.
 *
 * The important detail: in `@Preview` / layout-inspection mode — and whenever [url] is
 * missing — it paints a neutral box and never touches the network. That is what makes
 * previews of *any* screen that shows remote images render reliably.
 */
@Composable
fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)

    if (LocalInspectionMode.current || url.isNullOrBlank()) {
        Box(modifier.then(placeholder))
        return
    }

    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.then(placeholder),
        contentScale = contentScale,
    )
}

@Preview(name = "NetworkImage (inspection placeholder)")
@Composable
private fun NetworkImagePreview() {
    // dynamicColor = false: Layoutlib renders dynamic schemes as near-black.
    FetchCloneTheme(dynamicColor = false) {
        NetworkImage(
            url = "https://example.com/photo.jpg",
            contentDescription = null,
            modifier = Modifier.size(120.dp),
        )
    }
}
