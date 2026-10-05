package com.havivon.newplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }
            ?.let { runCatching { it.get() }.getOrNull() }

    /** Files that arrived before the connection to the player was ready. */
    private var pendingUris: List<Uri>? = null

    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var trackNumber: TextView
    private lateinit var playPause: MaterialButton
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 500)
        }
    }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach(::keepAccess)
                playFiles(uris)
            }
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateUi()

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MainActivity, R.string.cannot_play, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        title = findViewById(R.id.title)
        time = findViewById(R.id.time)
        trackNumber = findViewById(R.id.track_number)
        playPause = findViewById(R.id.play_pause)
        previous = findViewById(R.id.previous)
        next = findViewById(R.id.next)

        // A single tap anywhere that is not a button pauses playback.
        findViewById<View>(R.id.root).setOnClickListener { view ->
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                c.pause()
            }
        }

        findViewById<View>(R.id.open).setOnClickListener { openPicker() }
        findViewById<View>(R.id.rewind_10).setOnClickListener { controller?.seekByMs(-10_000) }
        findViewById<View>(R.id.rewind_5).setOnClickListener { controller?.seekByMs(-5_000) }
        playPause.setOnClickListener { togglePlayPause() }
        previous.setOnClickListener { controller?.seekToPreviousMediaItem() }
        next.setOnClickListener { controller?.seekToNextMediaItem() }

        if (savedInstanceState == null) handleViewIntent(intent)
        askForNotificationPermission()
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ onControllerReady() }, ContextCompat.getMainExecutor(this))
        handler.post(clock)
    }

    override fun onStop() {
        handler.removeCallbacks(clock)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun onControllerReady() {
        val c = controller ?: return
        c.addListener(playerListener)
        pendingUris?.let {
            pendingUris = null
            playFiles(it)
        }
        updateUi()
    }

    /** Handles "Open with" from a file manager or another app. */
    private fun handleViewIntent(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        keepAccess(uri)
        playFiles(listOf(uri))
    }

    private fun openPicker() {
        pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac", "video/mp4"))
    }

    private fun playFiles(uris: List<Uri>) {
        val c = controller
        if (c == null) {
            pendingUris = uris
            return
        }
        c.setMediaItems(uris.map { audioItem(this, it) })
        c.prepare()
        c.play()
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> openPicker()
            showsPause(c) -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }
    }

    /** Keeps permission to read the file so it can be resumed after the app restarts. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showsPause(c: Player) =
        c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE

    private fun updateUi() {
        val c = controller
        val hasMedia = c != null && c.mediaItemCount > 0

        title.text = c?.mediaMetadata?.title?.takeIf { hasMedia } ?: getString(R.string.no_file)

        if (c != null && showsPause(c)) {
            playPause.setIconResource(androidx.media3.session.R.drawable.media3_icon_pause)
            playPause.contentDescription = getString(R.string.pause)
        } else {
            playPause.setIconResource(androidx.media3.session.R.drawable.media3_icon_play)
            playPause.contentDescription = getString(R.string.play)
        }

        val count = c?.mediaItemCount ?: 0
        trackNumber.text =
            if (count > 1) getString(R.string.track_of, c!!.currentMediaItemIndex + 1, count) else ""
        previous.isEnabled = c?.hasPreviousMediaItem() == true
        next.isEnabled = c?.hasNextMediaItem() == true
        updateTime()
    }

    private fun updateTime() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            time.text = ""
            return
        }
        val position = formatTime(c.currentPosition)
        time.text = if (c.duration == C.TIME_UNSET) position else "$position / ${formatTime(c.duration)}"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
