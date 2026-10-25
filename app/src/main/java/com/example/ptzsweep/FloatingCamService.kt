package com.example.ptzsweep

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets the app UI know whether the floating window is showing. */
object OverlayState {
    var running by mutableStateOf(false)
}

/** A draggable always-on-top window with live video, a sweep button and a screenshot button. */
class FloatingCamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var store: SettingsStore
    private lateinit var lp: WindowManager.LayoutParams

    private var root: FrameLayout? = null
    private var videoLayout: VLCVideoLayout? = null
    private var statusView: TextView? = null
    private var sweepButton: TextView? = null

    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var client: OnvifClient? = null
    private var clientKey = ""
    private var sweepJob: Job? = null
    private var mainUri: String? = null
    private var currentUri = ""
    private var triedMain = false
    private var big = false

    // drag state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(applicationContext)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                toast("Please allow \"Display over other apps\" first")
                stopSelf()
                return START_NOT_STICKY
            }
            buildOverlay()
            OverlayState.running = true
            startVideo()
        }
        return START_NOT_STICKY
    }

    // ---- foreground notification ----

    private fun startAsForeground() {
        val channelId = "ptz_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Floating camera", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingCamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PTZ Sweep floating camera")
            .setContentText("The floating video window is on")
            .addAction(0, "Close", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
    }

    // ---- window ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(0x66000000)
            cornerRadius = dp(16).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun buildOverlay() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val video = VLCVideoLayout(this)
        container.addView(video, FrameLayout.LayoutParams(match, match))
        videoLayout = video

        // Invisible layer over the video: touching and dragging it moves the window.
        val dragLayer = View(this)
        container.addView(dragLayer, FrameLayout.LayoutParams(match, match))
        dragLayer.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - downX).toInt()
                    lp.y = startY + (ev.rawY - downY).toInt()
                    wm.updateViewLayout(container, lp)
                }
            }
            true
        }

        // status (top-left)
        val status = TextView(this).apply {
            text = "Starting…"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setBackgroundColor(0x66000000)
        }
        container.addView(status, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START))
        statusView = status

        // resize + close (top-right)
        val topRow = LinearLayout(this)
        val sizeBtn = pill("⤢") { toggleSize(container) }
        val closeBtn = pill("✕") { stopSelf() }
        topRow.addView(sizeBtn)
        topRow.addView(closeBtn, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(6) })
        container.addView(
            topRow,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4); rightMargin = dp(4)
            }
        )

        // sweep (bottom-left)
        val sweep = pill("▶ Sweep") { toggleSweep() }
        container.addView(
            sweep,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(6); leftMargin = dp(6)
            }
        )
        sweepButton = sweep

        // screenshot (bottom-right): a see-through round button over the video
        val shot = TextView(this).apply {
            text = "📷"
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x44FFFFFF)
                setStroke(dp(2), 0x99FFFFFF.toInt())
            }
            setOnClickListener { takeScreenshot() }
        }
        container.addView(
            shot,
            FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(6); rightMargin = dp(6)
            }
        )

        lp = WindowManager.LayoutParams(
            dp(240), dp(135), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        wm.addView(container, lp)
        root = container
    }

    private fun toggleSize(container: View) {
        big = !big
        lp.width = dp(if (big) 340 else 240)
        lp.height = dp(if (big) 191 else 135)
        wm.updateViewLayout(container, lp)
    }

    private fun setStatus(text: String) {
        scope.launch { statusView?.text = text }
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    // ---- video ----

    private suspend fun ensureClient(): OnvifClient = withContext(Dispatchers.IO) {
        val c = store.load()
        val key = "${c.host}|${c.port}|${c.user}|${c.pass}"
        client?.takeIf { key == clientKey }
            ?: OnvifClient(c.host, c.port, c.user, c.pass).also {
                it.connect()
                client = it
                clientKey = key
            }
    }

    private fun ensurePlayer(): MediaPlayer {
        vlcPlayer?.let { return it }
        val lib = LibVLC(applicationContext, arrayListOf<String>())
        val mp = MediaPlayer(lib)
        mp.setEventListener { e -> onVlcEvent(e) }
        // TextureView (true) so a screenshot can be taken from the picture
        mp.attachViews(videoLayout!!, null, false, true)
        libVlc = lib
        vlcPlayer = mp
        return mp
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        if (root == null) return
        when (e.type) {
            MediaPlayer.Event.Playing -> setStatus("LIVE")
            MediaPlayer.Event.Buffering -> setStatus(if (e.buffering < 100f) "Buffering…" else "LIVE")
            MediaPlayer.Event.EncounteredError,
            MediaPlayer.Event.EndReached -> scope.launch { onVideoFailed() }
        }
    }

    private fun onVideoFailed() {
        if (root == null) return
        val fallback = mainUri
        if (!triedMain && fallback != null && fallback != currentUri) {
            triedMain = true
            setStatus("Trying full quality…")
            play(fallback)
            return
        }
        setStatus("Video failed")
        runCatching { vlcPlayer?.stop() }
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

    private fun startVideo() {
        triedMain = false
        setStatus("Finding stream…")
        scope.launch {
            try {
                val (sub, main) = withContext(Dispatchers.IO) {
                    val c = ensureClient()
                    c.streamUri(preferSub = true) to c.streamUri(preferSub = false)
                }
                mainUri = main
                play(sub)
                setStatus("Connecting…")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                setStatus("Error: ${e.message}")
            }
        }
    }

    // ---- sweep ----

    private fun toggleSweep() {
        if (sweepJob?.isActive == true) sweepJob?.cancel() else startSweep()
    }

    private fun startSweep() {
        sweepButton?.text = "■ Stop"
        sweepJob = scope.launch(Dispatchers.IO) {
            try {
                val c = ensureClient()
                var direction = 1f
                var first = true
                while (isActive) {
                    val cfg = store.load()
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
                setStatus("Sweep error: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    runCatching { client?.stop() }
                    withContext(Dispatchers.Main) { sweepButton?.text = "▶ Sweep" }
                }
            }
        }
    }

    // ---- screenshot ----

    private fun findTextureView(v: View?): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun takeScreenshot() {
        val tv = findTextureView(videoLayout)
        if (tv == null || !tv.isAvailable || tv.width == 0 || tv.height == 0) {
            toast("Video isn't ready yet")
            return
        }
        val w = 1280
        val h = (w.toFloat() * tv.height / tv.width).toInt().coerceAtLeast(1)
        val bmp = try { tv.getBitmap(w, h) } catch (t: Throwable) { null }
        if (bmp == null) {
            toast("Couldn't capture the picture")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = saveBitmap(bmp)
            withContext(Dispatchers.Main) {
                toast(
                    if (!ok) "Couldn't save the screenshot"
                    else if (Build.VERSION.SDK_INT >= 29) "Saved to Pictures/PtzSweep"
                    else "Screenshot saved in the app's Pictures folder"
                )
            }
        }
    }

    private fun saveBitmap(bmp: Bitmap): Boolean = try {
        val name = "PtzSweep_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PtzSweep")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri != null && contentResolver.openOutputStream(uri)!!.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PtzSweep").apply { mkdirs() }
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    } catch (e: Exception) {
        false
    }

    // ---- cleanup ----

    override fun onDestroy() {
        sweepJob?.cancel()
        val c = client
        Thread { runCatching { c?.stop() } }.start() // make sure the camera stops moving
        runCatching { vlcPlayer?.stop() }
        runCatching { vlcPlayer?.detachViews() }
        runCatching { vlcPlayer?.release() }
        runCatching { libVlc?.release() }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        OverlayState.running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.ptzsweep.STOP_FLOATING"
    }
}
