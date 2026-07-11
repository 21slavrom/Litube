package com.hhst.youtubelite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.ui.browser.BrowserScreen
import com.hhst.youtubelite.ui.theme.LiteTheme

/**
 * Single-activity host for the Compose UI.
 *
 * Enables edge-to-edge display; [BrowserScreen] applies safe drawing insets so
 * content clears system bars and cutouts.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen()
                }
            }
        }
    }
}
