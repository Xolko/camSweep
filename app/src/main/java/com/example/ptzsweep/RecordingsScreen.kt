package com.example.ptzsweep

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.5f, 1f, 2f, 4f, 8f)

@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    var fileName by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "This file can't be played (${e.errorCodeName})."
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    fun applySpeed(v: Float) {
        speed = v
        player.playbackParameters = PlaybackParameters(v)
        player.volume = if (v > 2f) 0f else 1f // fast audio is just noise
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            error = ""
            fileName = displayName(context, uri)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) sliderPos = player.currentPosition.toFloat()
            durationMs = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(250)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Recordings", style = MaterialTheme.typography.headlineSmall)

        if (fileName == null) {
            Panel {
                Text("Watch a saved clip at up to 8× speed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Save a recording from your camera app (or any video) to your phone, then open it here. " +
                        "Use the speed buttons to skim through hours of footage quickly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open a recording")
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp)).background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Panel {
                Text(fileName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)

                val max = durationMs.toFloat().coerceAtLeast(1f)
                Slider(
                    value = sliderPos.coerceIn(0f, max),
                    onValueChange = { dragging = true; sliderPos = it },
                    onValueChangeFinished = { player.seekTo(sliderPos.toLong()); dragging = false },
                    valueRange = 0f..max
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(sliderPos.toLong()), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                    }) { Text("−10s") }
                    Button(onClick = {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        if (player.isPlaying) player.pause() else player.play()
                    }) { Text(if (playing) "❚❚  Pause" else "▶  Play") }
                    OutlinedButton(onClick = {
                        player.seekTo((player.currentPosition + 10_000).coerceAtMost(durationMs))
                    }) { Text("+10s") }
                }

                Text("Speed", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEach { v ->
                        FilterChip(
                            selected = speed == v,
                            onClick = { applySpeed(v) },
                            label = { Text(if (v == 0.5f) "0.5×" else "${v.toInt()}×") }
                        )
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open another recording")
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Recording"

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
