/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.audio

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

/**
 * Music that keeps playing when the player window is closed, with the system's media notification
 * and lock-screen controls (user request: a music player like MT's). The window only controls it.
 */
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
            // Unplugged headphones pause instead of playing out loud.
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
                // A file that cannot be played is skipped rather than stopping the whole folder.
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "Playing=$isPlaying item=${exo.currentMediaItemIndex + 1}/${exo.mediaItemCount}")
            }
        })
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).putExtra(AudioPlayerActivity.EXTRA_ATTACH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, TitledPlayer(exo))
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Swiped away while paused or with nothing loaded: nothing left to keep alive.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        deleteCopies(cacheDir, keep = null)
        super.onDestroy()
    }

    /** Files without tags (WAV, many MP3s) still show their name in the notification. */
    @OptIn(UnstableApi::class)
    private class TitledPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getMediaMetadata(): MediaMetadata {
            val metadata = super.getMediaMetadata()
            if (metadata.title != null) return metadata
            val name = metadata.displayTitle ?: currentMediaItem?.mediaId?.substringAfterLast('/')
            return if (name == null) metadata else metadata.buildUpon().setTitle(name).build()
        }
    }

    companion object {
        private const val TAG = "OMMusic"

        /** Songs from archives or remote storage are copied to the cache to be played. */
        const val COPY_PREFIX = "audio_preview_"

        fun deleteCopies(cacheDir: File, keep: File?) {
            cacheDir.listFiles()?.forEach {
                if (it.name.startsWith(COPY_PREFIX) && it != keep) it.delete()
            }
        }
    }
}
