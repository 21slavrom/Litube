package com.hhst.youtubelite.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.ui.theme.LiteTheme
import com.hhst.youtubelite.ui.theme.YtRed
import org.koin.androidx.compose.koinViewModel

/**
 * Stateful home screen entry. Resolves [MainViewModel] from Koin.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MainScreenContent(uiState = uiState)
}

/**
 * Stateless home screen: product title plus a single label line.
 *
 * @param uiState values to render.
 * @param modifier optional root modifier.
 */
@Composable
fun MainScreenContent(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = uiState.title,
                style = MaterialTheme.typography.displaySmall,
                color = YtRed,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    LiteTheme {
        MainScreenContent(
            uiState = MainUiState(
                title = "Litube",
                label = "v3.0.0-devx · debug",
            ),
        )
    }
}
