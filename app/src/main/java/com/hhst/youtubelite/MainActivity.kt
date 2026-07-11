package com.hhst.youtubelite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hhst.youtubelite.ui.main.MainScreen
import com.hhst.youtubelite.ui.theme.LiteTheme

/**
 * Single activity host for the Compose UI tree.
 *
 * Edge-to-edge is enabled so content can draw behind system bars; individual
 * screens are responsible for applying window insets via Scaffold or padding.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
