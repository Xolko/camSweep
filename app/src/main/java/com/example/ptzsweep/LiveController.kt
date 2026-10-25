package com.example.ptzsweep

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveController(
    context: Context,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(store.load())
        private set

    // ---- video ----
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { volume = 0f }
    var videoOn by mutableStateOf(false)
        private set
    var videoStatus by mutableStateOf("")
        private set

    // ---- manual control + sweep ----
    var message by mutableStateOf("")
        private set
    var sweeping by mutableStateOf(false)
        private set
    var sweepStatus by mutableStateOf("Ready")
        private set
    var stopAfterMin by mutableIntStateOf(0) // 0 = endless

    private var client: OnvifClient? = null
    private var clientKey = ""
    private var moveJob: Job? = null
    private var sweepJob: Job? = null

    private var mainUri: String? = null
    private var currentUri = ""
    private var triedMain = false

    /** Never show the camera login, even inside error messages. */
    private fun hideLogin(text: String) = text.replace(Regex("""://[^/@\s]*@"""), "://")

    private fun play(uri: String) {
        currentUri = uri
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val fallback = mainUri
                if (!triedMain && fallback != null && fallback != currentUri) {
                    triedMain = true
                    videoStatus = "Trying full-quality stream…"
                    scope.launch { play(fallback) }
                    return
                }
                videoOn = false
                videoStatus = hideLogin(
                    "Couldn't play video.\n" +
                        "Error: ${error.errorCodeName}\n" +
                        "Address: $currentUri\n" +
                        (error.cause?.message ?: "")
                )
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!videoOn) return
                when (state) {
                    Player.STATE_READY -> videoStatus = "LIVE"
                    Player.STATE_BUFFERING -> videoStatus = "Buffering…"
                }
            }
        })
    }

    fun reloadSettings() { settings = store.load() }
    fun setSpeed(v: Float) { settings = settings.copy(speed = v) }
    fun commitSpeed() { store.save(settings) }

    private suspend fun ensureClient(): OnvifClient = withContext(Dispatchers.IO) {
        val c = settings
        val key = "${c.host}|${c.port}|${c.user}|${c.pass}"
        client?.takeIf { key == clientKey }
            ?: OnvifClient(c.host, c.port, c.user, c.pass).also {
                it.connect()
                client = it
                clientKey = key
            }
    }

    // ---- video ----

    fun startVideo() {
        videoOn = true
        triedMain = false
        videoStatus = "Finding stream…"
        scope.launch {
            try {
                val (sub, main) = withContext(Dispatchers.IO) {
                    val c = ensureClient()
                    c.streamUri(preferSub = true) to c.streamUri(preferSub = false)
                }
                mainUri = main
                play(sub) // the lighter stream first; it decodes more reliably on phones
                videoStatus = "Connecting…"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                videoOn = false
                videoStatus = hideLogin("Video error: ${e.message}")
            }
        }
    }

    fun stopVideo() {
        if (!videoOn && videoStatus.isEmpty()) return
        player.stop()
        player.clearMediaItems()
        videoOn = false
        videoStatus = ""
    }

    // ---- manual control (hold a button to move, release to stop) ----

    fun holdMove(x: Float, y: Float) {
        if (sweeping) return
        moveJob = scope.launch(Dispatchers.IO) {
            try {
                val c = ensureClient()
                c.move(x * settings.speed, y * settings.speed)
                message = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Error: ${e.message}"
            }
        }
    }

    fun releaseMove() {
        val pending = moveJob
        scope.launch(Dispatchers.IO) {
            pending?.join() // make sure the stop is sent after the move was
            runCatching { client?.stop() }
        }
    }

    fun emergencyStop() {
        sweepJob?.cancel()
        scope.launch(Dispatchers.IO) { runCatching { ensureClient().stop() } }
    }

    // ---- auto sweep ----

    fun toggleSweep() { if (sweeping) sweepJob?.cancel() else startSweep() }

    private fun startSweep() {
        sweeping = true
        sweepJob = scope.launch(Dispatchers.IO) {
            var failed = false
            try {
                sweepStatus = "Connecting…"
                val c = ensureClient()
                val startedAt = System.currentTimeMillis()
                val limitMs = stopAfterMin * 60_000L
                var direction = 1f
                var first = true // first leg is half length: sweep around the start position
                while (isActive) {
                    if (limitMs > 0 && System.currentTimeMillis() - startedAt >= limitMs) break
                    val cfg = settings
                    sweepStatus = if (direction > 0) "Sweeping right →" else "← Sweeping left"
                    c.move(direction * cfg.speed, 0f)
                    val leg = if (first) cfg.legSeconds / 2 else cfg.legSeconds
                    delay((leg * 1000).toLong())
                    c.stop()
                    delay((cfg.pauseSeconds * 1000).toLong())
                    direction = -direction
                    first = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                sweepStatus = "Error: ${e.message}"
            } finally {
                withContext(NonCancellable) { runCatching { client?.stop() } }
                sweeping = false
                if (!failed) sweepStatus = "Stopped"
            }
        }
    }

    fun release() {
        sweepJob?.cancel()
        player.release()
    }
}
