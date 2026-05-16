package com.tangotori.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tangotori.app.data.sudachi.FirstLaunchDictionaryDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

@Composable
fun FirstLaunchScreen(
    downloader: FirstLaunchDictionaryDownloader,
    onReady: () -> Unit,
) {
    // For Stage 1 buildability we expose a manual "Download Now" button that
    // collects the downloader Flow. Wire this to a real DownloadViewModel later.
    val progressFlow = remember { MutableStateFlow<Int>(-1) }
    val pct by progressFlow.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Tango Tori", style = MaterialTheme.typography.titleLarge)
        Text(
            "First-time setup: dictionaries (~50 MB)",
            modifier = Modifier.padding(top = 8.dp),
        )
        if (pct >= 0) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.padding(top = 24.dp).fillMaxSize(0.7f),
            )
            Text("$pct%", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = onReady,
                modifier = Modifier.padding(top = 24.dp),
            ) { Text("Download Now") }
        }
    }
}
