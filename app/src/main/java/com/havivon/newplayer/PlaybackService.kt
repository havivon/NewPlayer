package com.havivon.newplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays audio in the background. Media3 turns the session into a media notification, which also
 * provides the controls on the lock screen, Bluetooth headsets and car displays.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var store: PlaybackStore
    private val handler = Handler(Looper.getMainLooper())

    private val periodicSave = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this)

        // Index seeking keeps short rewinds accurate in variable-bitrate MP3 files, and constant
        // bitrate seeking makes MP3 files without a seek table seekable at all.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL) // keep playing while the screen is off
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveState()
                handler.removeCallbacks(periodicSave)
                if (isPlaying) handler.postDelayed(periodicSave, SAVE_INTERVAL_MS)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveState()
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(lockScreenButtons())
            .build()

        restoreState(player)
    }

    /** Rewind 10 replaces "previous" on the lock screen; rewind 5 goes next to it. */
    private fun lockScreenButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName(getString(R.string.rewind_10))
            .setSessionCommand(REWIND_10)
            .setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_5)
            .setDisplayName(getString(R.string.rewind_5))
            .setSessionCommand(REWIND_5)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        super.onTaskRemoved(rootIntent) // keeps the service alive only while playing
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicSave)
        saveState()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun restoreState(player: Player) {
        val state = store.load() ?: return
        player.setMediaItems(state.uris.map { audioItem(this, it) }, state.index, state.positionMs)
        player.prepare()
    }

    private fun saveState() {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) return
        val uris = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).localConfiguration?.uri
        }
        store.save(uris, player.currentMediaItemIndex, player.currentPosition)
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(REWIND_10)
                .add(REWIND_5)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                REWIND_10.customAction -> session.player.seekByMs(-10_000)
                REWIND_5.customAction -> session.player.seekByMs(-5_000)
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Controllers send items without their URI; rebuild them from the request metadata.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        // Lets Android 13+ offer a "resume" control for the last played file after a reboot.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val state = store.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    state.uris.map { audioItem(this@PlaybackService, it) },
                    state.index,
                    state.positionMs,
                ),
            )
        }
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L
        val REWIND_10 = SessionCommand("com.havivon.newplayer.REWIND_10", Bundle.EMPTY)
        val REWIND_5 = SessionCommand("com.havivon.newplayer.REWIND_5", Bundle.EMPTY)
    }
}

/** Seeks relative to the current position, staying inside the track. */
fun Player.seekByMs(deltaMs: Long) {
    var target = (currentPosition + deltaMs).coerceAtLeast(0L)
    if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
    seekTo(target)
}
