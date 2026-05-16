package com.tangotori.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(16.dp)) {
            Text("Default Deck, Furigana display toggle, POS color toggle, About — TODO.")
        }
    }
}
