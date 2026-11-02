package io.github.jpcarter34us.audacious

import android.app.Application
import io.github.jpcarter34us.audacious.engine.AudConfig
import io.github.jpcarter34us.audacious.engine.EffectsModel
import io.github.jpcarter34us.audacious.engine.EqualizerModel
import io.github.jpcarter34us.audacious.library.DaapClient
import io.github.jpcarter34us.audacious.library.ImageRef
import io.github.jpcarter34us.audacious.library.LibrarySource
import io.github.jpcarter34us.audacious.library.LocalLibrary
import io.github.jpcarter34us.audacious.library.MusicAssistantClient
import io.github.jpcarter34us.audacious.library.SubsonicClient
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.player.Track
import io.github.jpcarter34us.audacious.player.TrackSource
import io.github.jpcarter34us.audacious.sendspin.SendspinClient
import io.github.jpcarter34us.audacious.util.Http
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.Secrets

/** The app's models, created once for the process (the iOS port's
 *  AudaciousMobileApp state objects). */
class App : Application() {
    companion object {
        lateinit var app: App
            private set
    }

    lateinit var player: Player
        private set
    lateinit var effects: EffectsModel
        private set
    lateinit var equalizer: EqualizerModel
        private set
    lateinit var local: LocalLibrary
        private set
    lateinit var subsonic: SubsonicClient
        private set
    lateinit var daap: DaapClient
        private set
    lateinit var musicAssistant: MusicAssistantClient
        private set
    lateinit var sendspin: SendspinClient
        private set

    val sources: List<LibrarySource> get() = listOf(local, subsonic, daap, musicAssistant)

    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        Secrets.init(this)
        Http.init(this)
        AudConfig.load(this)
        effects = EffectsModel()
        equalizer = EqualizerModel(this)
        player = Player(this)
        local = LocalLibrary(this, player)
        subsonic = SubsonicClient(player)
        daap = DaapClient(this, player)
        musicAssistant = MusicAssistantClient()
        sendspin = SendspinClient(player, musicAssistant)

        player.onActivate = { PlaybackService.start(this) }
        player.onTrackStarted = { subsonic.trackStarted(it) }
        player.artworkProvider = { artwork(it) }
        // A Music Assistant sign-in changes the proxy route's server and token.
        musicAssistant.changed.watch {
            if (sendspin.enabled && sendspin.route == SendspinClient.Route.MUSIC_ASSISTANT &&
                musicAssistant.status.state == MusicAssistantClient.State.CONNECTED &&
                sendspin.connection == SendspinClient.Connection.ERROR
            ) sendspin.restart()
        }
        if (sendspin.enabled) PlaybackService.start(this)
    }

    /** The cover for a playlist entry (may block). */
    fun artwork(track: Track): ImageRef? = when (val s = track.source) {
        is TrackSource.Local -> ImageRef.Embedded(s.path)
        is TrackSource.Subsonic -> subsonic.cover(track.artworkKey)
        is TrackSource.Daap -> daap.artwork(s.share, s.id)
        is TrackSource.Url -> track.artworkKey?.takeIf { it.startsWith("http") }?.let { ImageRef.Remote(it) }
    }
}
