package com.example.ptzsweep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Root() } }
    }
}

@Composable
fun Root() {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(appContext) }
    val live = remember { LiveController(appContext, store, scope) }
    DisposableEffect(live) { onDispose { live.release() } }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Close the video when leaving the Live tab to save battery and data. A running sweep carries on.
    LaunchedEffect(tab) { if (tab != 0) live.stopVideo() }

    val tabs = listOf("Live" to "📹", "Recordings" to "🎞", "Settings" to "⚙")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, glyph) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(glyph, fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> LiveScreen(live)
                1 -> RecordingsScreen()
                else -> SettingsScreen(store) { live.reloadSettings() }
            }
        }
    }
}
