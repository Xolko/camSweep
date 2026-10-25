package com.example.ptzsweep

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class LiveController(
    context: Context,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var settings by mutableStateOf(store.load())
        private set

    // ---- video (libVLC) ----
    var videoOn by mutableStateOf(false)
        private set
    var videoStatus by mutableStateOf("")
        private set
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    @Volatile private var attached = false
    private var mainUri: String? = null
    private var currentUri = ""
    private var triedMain = false

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

    /** Never show the camera login, even inside error messages. */
    private fun hideLogin(text: String) = text.replace(Regex("""://[^/@\s]*@"""), "://")

    private fun ensurePlayer(): MediaPlayer {
        vlcPlayer?.let { return it }
        val lib = LibVLC(appContext, arrayListOf<String>())
        val mp = MediaPlayer(lib)
        mp.setEventListener { e -> onVlcEvent(e) }
        libVlc = lib
        vlcPlayer = mp
        return mp
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        if (!videoOn) return
        when (e.type) {
            MediaPlayer.Event.Playing -> videoStatus = "LIVE"
            MediaPlayer.Event.Buffering ->
                videoStatus = if (e.buffering < 100f) "Buffering…" else "LIVE"
            MediaPlayer.Event.EncounteredError,
            MediaPlayer.Event.EndReached -> scope.launch { onVideoFailed() }
        }
    }

    private fun onVideoFailed() {
        if (!videoOn) return
        val fallback = mainUri
        if (!triedMain && fallback != null && fallback != currentUri) {
            triedMain = true
            videoStatus = "Trying full-quality stream…"
            play(fallback)
            return
        }
        videoOn = false
        runCatching { vlcPlayer?.stop() }
        videoStatus = hideLogin("Couldn't play video.\nAddress: $currentUri")
    }

    private fun play(uri: String) {
        currentUri = uri
        val mp = ensurePlayer()
        val media = Media(libVlc, Uri.parse(uri))
        media.addOption(":network-caching=300")
        mp.setMedia(media)
        media.release()
        mp.play()
        mp.setVolume(0)
    }

    /** Called by the video view once it is on screen. */
    fun attach(layout: VLCVideoLayout) {
        try {
            ensurePlayer().attachViews(layout, null, false, false)
            attached = true
        } catch (t: Throwable) {
            videoOn = false
            videoStatus = "Video player failed to start: ${t.message}"
        }
    }

    fun detach() {
        attached = false
        runCatching { vlcPlayer?.detachViews() }
    }

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
                var waited = 0
                while (!attached && waited < 2000) { delay(50); waited += 50 }
                play(sub) // the lighter stream first
                videoStatus = "Connecting…"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                videoOn = false
                videoStatus = hideLogin("Video error: ${e.message}")
            }
        }
    }

    fun stopVideo() {
        if (!videoOn && videoStatus.isEmpty()) return
        videoOn = false
        videoStatus = ""
        runCatching { vlcPlayer?.stop() }
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
        runCatching { vlcPlayer?.stop() }
        runCatching { vlcPlayer?.release() }
        runCatching { libVlc?.release() }
    }
}
