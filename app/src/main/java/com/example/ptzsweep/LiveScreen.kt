package com.example.ptzsweep

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun LiveScreen(live: LiveController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        VideoCard(live)
        ControlCard(live)
        SweepCard(live)
        FloatingCard(live)
    }
}

@Composable
private fun VideoCard(live: LiveController) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp)).background(Color.Black)
    ) {
        if (live.videoOn) {
            val ctx = LocalContext.current
            val layout = remember { VLCVideoLayout(ctx) }
            DisposableEffect(layout) {
                live.attach(layout)
                onDispose { live.detach() }
            }
            AndroidView(factory = { layout }, modifier = Modifier.fillMaxSize())
            StatusPill(live.videoStatus, Modifier.align(Alignment.TopStart).padding(10.dp))
            TextButton(
                onClick = live::stopVideo,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
            ) { Text("✕ Close", color = Color.White) }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = live::startVideo) { Text("▶  Start live video") }
                if (live.videoStatus.isNotEmpty()) {
                    Text(
                        live.videoStatus,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Surface(shape = RoundedCornerShape(50), color = Color(0xAA000000), modifier = modifier) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ControlCard(live: LiveController) {
    val enabled = !live.sweeping
    Panel("Manual control") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HoldButton("▲", enabled, { live.holdMove(0f, 1f) }, live::releaseMove)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HoldButton("◀", enabled, { live.holdMove(-1f, 0f) }, live::releaseMove)
                Button(
                    onClick = live::emergencyStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(68.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = CircleShape
                ) { Text("■", fontSize = 22.sp, color = MaterialTheme.colorScheme.onError) }
                HoldButton("▶", enabled, { live.holdMove(1f, 0f) }, live::releaseMove)
            }
            HoldButton("▼", enabled, { live.holdMove(0f, -1f) }, live::releaseMove)
        }
        Text(
            if (enabled) "Hold a button to move, release to stop." else "Manual control is off while sweeping.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Speed  ${"%.2f".format(live.settings.speed)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = live.settings.speed,
            onValueChange = live::setSpeed,
            onValueChangeFinished = live::commitSpeed,
            valueRange = 0.1f..1f
        )
        if (live.message.isNotEmpty()) {
            Text(live.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HoldButton(label: String, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        !enabled -> Color(0xFF151B24)
        pressed -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF2A3545)
    }
    Box(
        Modifier.size(68.dp).clip(CircleShape).background(bg)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onPress = {
                        pressed = true
                        onPress()
                        tryAwaitRelease()
                        pressed = false
                        onRelease()
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 24.sp,
            color = when {
                !enabled -> Color(0xFF4A5666)
                pressed -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun SweepCard(live: LiveController) {
    val s = live.settings
    Panel("Auto sweep") {
        Text(
            "Pans left and right, ${s.legSeconds}s per side. Change the time in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Stop automatically after", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScrollCompat(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0 to "Never", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour").forEach { (min, label) ->
                FilterChip(
                    selected = live.stopAfterMin == min,
                    onClick = { live.stopAfterMin = min },
                    enabled = !live.sweeping,
                    label = { Text(label) }
                )
            }
        }
        Button(
            onClick = live::toggleSweep,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = if (live.sweeping)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)
            else ButtonDefaults.buttonColors()
        ) {
            Text(if (live.sweeping) "STOP SWEEP" else "START SWEEP", style = MaterialTheme.typography.titleMedium)
        }
        Text(live.sweepStatus, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))


@Composable
private fun FloatingCard(live: LiveController) {
    val context = LocalContext.current
    val running = OverlayState.running
    Panel("Floating window") {
        Text(
            "Shows the live video in a small window that stays on top of other apps, " +
                "with a sweep button and a see-through screenshot button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                when {
                    OverlayState.running ->
                        context.stopService(Intent(context, FloatingCamService::class.java))
                    !Settings.canDrawOverlays(context) ->
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    else -> {
                        live.stopVideo()
                        ContextCompat.startForegroundService(
                            context, Intent(context, FloatingCamService::class.java)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                when {
                    running -> "Hide floating window"
                    !Settings.canDrawOverlays(context) -> "Allow, then show floating window"
                    else -> "Show floating window"
                }
            )
        }
        Text(
            "After it appears, press Home to use it over other apps. Drag it to move it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
