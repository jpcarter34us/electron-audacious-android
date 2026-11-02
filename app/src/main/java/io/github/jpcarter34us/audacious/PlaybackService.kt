package io.github.jpcarter34us.audacious

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.sendspin.SendspinClient
import io.github.jpcarter34us.audacious.ui.MainActivity
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.Images

/**
 * Keeps playing in the background (the iOS port's audio session and
 * remote commands): a foreground service with a notification, lock-screen
 * and media-button controls (MediaSession on Android 5+, RemoteControlClient
 * before), audio focus, and a pause when headphones are unplugged. It stays
 * up while something plays, and while this device is a Sendspin speaker so
 * Music Assistant can start playing here.
 */
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        const val ACTION_TOGGLE = "io.github.jpcarter34us.audacious.TOGGLE"
        const val ACTION_NEXT = "io.github.jpcarter34us.audacious.NEXT"
        const val ACTION_PREVIOUS = "io.github.jpcarter34us.audacious.PREVIOUS"
        const val ACTION_STOP = "io.github.jpcarter34us.audacious.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.w("service", "cannot start the playback service", e)
            }
        }

        /** Media buttons from a headset or the lock screen (Android 4.x). */
        fun handleKey(event: KeyEvent?): Boolean {
            if (event == null || event.action != KeyEvent.ACTION_DOWN) return false
            val p = App.app.player
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> p.togglePause()
                KeyEvent.KEYCODE_MEDIA_PLAY -> p.resume()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> p.next()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> p.previous()
                KeyEvent.KEYCODE_MEDIA_STOP -> p.stop()
                else -> return false
            }
            return true
        }
    }

    private val app get() = App.app
    private lateinit var audio: AudioManager
    private var session: MediaSession? = null
    private var remote: RemoteControlClient? = null
    private var unwatch = ArrayList<() -> Unit>()
    private var hasFocus = false
    private var resumeOnGain = false
    private var foreground = false
    private var artFor: String? = null
    private var art: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
        goForeground()
        if (Build.VERSION.SDK_INT >= 21) setUpSession() else setUpRemoteControl()
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        unwatch.add(app.player.changed.watch { update() })
        unwatch.add(app.sendspin.changed.watch { update() })
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> app.player.togglePause()
            ACTION_NEXT -> app.player.next()
            ACTION_PREVIOUS -> app.player.previous()
            ACTION_STOP -> {
                app.player.stop()
                if (app.sendspin.enabled) app.sendspin.enabled = false
            }
            Intent.ACTION_MEDIA_BUTTON -> handleKey(intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT))
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unwatch.forEach { it() }
        try {
            unregisterReceiver(noisy)
        } catch (_: Exception) {
        }
        abandonFocus()
        if (Build.VERSION.SDK_INT >= 21) session?.release()
        if (Build.VERSION.SDK_INT < 21) {
            @Suppress("DEPRECATION")
            remote?.let { audio.unregisterRemoteControlClient(it) }
            @Suppress("DEPRECATION")
            audio.unregisterMediaButtonEventReceiver(ComponentName(this, MediaButtonReceiver::class.java))
        }
        super.onDestroy()
    }

    // MARK: State

    private val active get() = app.player.state != Player.State.STOPPED

    private fun update() {
        val p = app.player
        if (p.state == Player.State.PLAYING) requestFocus()
        updateSession()
        if (!active && !app.sendspin.enabled) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        goForeground()
        loadArt()
    }

    private fun loadArt() {
        val track = app.player.currentTrack ?: return
        val key = track.id + (track.artworkKey ?: "")
        if (artFor == key) return
        artFor = key
        art = null
        Bg.load({ app.artwork(track)?.let { Images.load(this, it, 256) } }) { r ->
            if (artFor == key) {
                art = r.getOrNull()
                updateSession()
                if (active || app.sendspin.enabled) goForeground()
            }
        }
    }

    // MARK: Notification

    private fun pending(action: String, code: Int): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(action), flags)
    }

    private fun buildNotification(): Notification {
        val p = app.player
        val track = p.currentTrack
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        b.setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags))
            .setDeleteIntent(pending(ACTION_STOP, 4))
            .setOngoing(p.state == Player.State.PLAYING || app.sendspin.enabled)
        if (Build.VERSION.SDK_INT >= 17) b.setShowWhen(false)
        if (active && track != null) {
            b.setContentTitle(track.title).setContentText(track.subtitle.ifEmpty { "Audacious" })
            art?.let { b.setLargeIcon(it) }
        } else {
            val s = app.sendspin
            b.setContentTitle("Audacious").setContentText(when (s.connection) {
                SendspinClient.Connection.CONNECTED -> "Ready as a Music Assistant speaker"
                SendspinClient.Connection.CONNECTING -> "Connecting to Music Assistant…"
                SendspinClient.Connection.ERROR -> s.error ?: "Speaker offline"
                SendspinClient.Connection.OFF -> "Stopped"
            })
        }
        if (active) {
            @Suppress("DEPRECATION")
            b.addAction(R.drawable.ic_previous, "Previous", pending(ACTION_PREVIOUS, 1))
            @Suppress("DEPRECATION")
            b.addAction(if (p.state == Player.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play,
                if (p.state == Player.State.PLAYING) "Pause" else "Play", pending(ACTION_TOGGLE, 2))
            @Suppress("DEPRECATION")
            b.addAction(R.drawable.ic_next, "Next", pending(ACTION_NEXT, 3))
        }
        if (Build.VERSION.SDK_INT >= 21) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC)
            val style = Notification.MediaStyle()
            session?.let { style.setMediaSession(it.sessionToken) }
            if (active) style.setShowActionsInCompactView(0, 1, 2)
            b.setStyle(style)
        }
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 16) b.build() else b.notification
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION, n)
        }
        foreground = true
    }

    private fun stopForegroundCompat() {
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        foreground = false
    }

    // MARK: Lock screen and media buttons

    @TargetApi(21)
    private fun setUpSession() {
        val s = MediaSession(this, "Audacious")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = app.player.resume()
            override fun onPause() = app.player.pause()
            override fun onSkipToNext() = app.player.next()
            override fun onSkipToPrevious() = app.player.previous()
            override fun onStop() = app.player.stop()
            override fun onSeekTo(pos: Long) = app.player.seek(pos / 1000.0)
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.isActive = true
        session = s
    }

    @Suppress("DEPRECATION")
    private fun setUpRemoteControl() {
        val receiver = ComponentName(this, MediaButtonReceiver::class.java)
        audio.registerMediaButtonEventReceiver(receiver)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
        val rc = RemoteControlClient(PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT))
        rc.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY or RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
            RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
            RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS or RemoteControlClient.FLAG_KEY_MEDIA_STOP)
        audio.registerRemoteControlClient(rc)
        remote = rc
    }

    private fun updateSession() {
        val p = app.player
        val track = p.currentTrack
        if (Build.VERSION.SDK_INT >= 21) {
            val s = session ?: return
            val state = when (p.state) {
                Player.State.PLAYING -> PlaybackState.STATE_PLAYING
                Player.State.PAUSED -> PlaybackState.STATE_PAUSED
                Player.State.STOPPED -> PlaybackState.STATE_STOPPED
            }
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
                    (if (p.isLive) 0L else PlaybackState.ACTION_SEEK_TO))
                .setState(state, (p.time * 1000).toLong(), if (p.state == Player.State.PLAYING) 1f else 0f)
                .build())
            if (track != null) {
                val m = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, ((p.duration ?: 0.0) * 1000).toLong())
                art?.let { m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                s.setMetadata(m.build())
            }
        } else {
            @Suppress("DEPRECATION")
            val rc = remote ?: return
            @Suppress("DEPRECATION")
            rc.setPlaybackState(when (p.state) {
                Player.State.PLAYING -> RemoteControlClient.PLAYSTATE_PLAYING
                Player.State.PAUSED -> RemoteControlClient.PLAYSTATE_PAUSED
                Player.State.STOPPED -> RemoteControlClient.PLAYSTATE_STOPPED
            })
            if (track != null) {
                @Suppress("DEPRECATION")
                val e = rc.editMetadata(true)
                e.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, track.title)
                e.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, track.artist ?: "")
                e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, track.album ?: "")
                e.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, ((p.duration ?: 0.0) * 1000).toLong())
                @Suppress("DEPRECATION")
                art?.let { e.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)) }
                e.apply()
            }
        }
    }

    // MARK: Audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val p = app.player
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                if (!p.isLive) p.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnGain = p.state == Player.State.PLAYING && !p.isLive
                if (resumeOnGain) p.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnGain) p.resume()
                resumeOnGain = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        if (hasFocus) return
        hasFocus = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        audio.abandonAudioFocus(focusListener)
        hasFocus = false
    }

    /** Headphones unplugged: pause, as every Android player does. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!app.player.isLive) app.player.pause()
        }
    }
}

/** Media buttons on Android 4.x (Android 5+ delivers them to the MediaSession). */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) PlaybackService.handleKey(intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT))
    }
}
