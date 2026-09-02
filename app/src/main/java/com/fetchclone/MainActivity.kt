package com.fetchclone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fetchclone.core.ui.theme.FetchCloneTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All screens are Compose destinations under [FetchCloneApp]'s
 * `NavHost`; this class only sets up the theme and edge-to-edge window.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FetchCloneTheme {
                FetchCloneApp()
            }
        }
    }
}
