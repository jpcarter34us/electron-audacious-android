package io.github.jpcarter34us.audacious.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.library.DaapClient
import io.github.jpcarter34us.audacious.library.MusicAssistantClient
import io.github.jpcarter34us.audacious.library.SubsonicClient
import io.github.jpcarter34us.audacious.sendspin.SendspinClient
import io.github.jpcarter34us.audacious.ui.Ui.add
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.text

/** Settings (the iOS port's SettingsView): libraries, sign-ins, and this
 *  device as a Music Assistant speaker. */
class SettingsScreen(activity: MainActivity) : Screen(activity) {
    private val app = App.app

    // Dynamic parts, rebuilt when their model changes.
    private val localBox = Ui.vertical(context)
    private val subsonicStatus = Ui.text(context, "", 13f, Ui.DIM)
    private val daapBox = Ui.vertical(context)
    private val maStatus = Ui.text(context, "", 13f, Ui.DIM)
    private val maPlayers = Ui.vertical(context)
    private val speakerBox = Ui.vertical(context)
    private val speakerName = Ui.field(context, "Speaker name", app.sendspin.name)
    private val speakerAddress = Ui.field(context, "Sendspin server (host or host:8927)", app.sendspin.directAddress, uri = true)

    override val view: View = Ui.scroll(context, Ui.vertical(context, 12).apply {
        add(Ui.text(context, "Settings", 22f, bold = true))
        add(Ui.heading(context, "On This Device"))
        add(Ui.card(context).apply { add(localBox) })
        add(Ui.heading(context, "Subsonic / Navidrome"))
        add(subsonicCard())
        add(Ui.heading(context, "DAAP (iTunes sharing, OwnTone)"))
        add(Ui.card(context).apply { add(daapBox) })
        add(Ui.heading(context, "Music Assistant"))
        add(maCard())
        add(Ui.heading(context, "Music Assistant Speaker (Sendspin)"))
        add(Ui.card(context).apply { add(speakerBox) })
        add(Ui.heading(context, "About"))
        add(Ui.card(context).apply {
            add(Ui.text(context, "Audacious for Android 1.0", 15f, bold = true))
            add(Ui.text(context, "Built on the audio core of the Audacious media player (BSD-2-Clause) and its effect plugins, " +
                "ported by way of ElectronPlayer and Audacious Mobile. Sendspin client ported from sendspin-js (Apache-2.0). " +
                "Decoders: dr_libs, stb_vorbis (public domain), libopus (BSD). Not affiliated with the Audacious developers.", 13f, Ui.DIM), top = 4)
        })
    })

    init {
        watch(app.local.changed.watch { refreshLocal() })
        watch(app.subsonic.changed.watch { refreshSubsonic() })
        watch(app.daap.changed.watch { refreshDaap() })
        watch(app.musicAssistant.changed.watch { refreshMA() })
        watch(app.sendspin.changed.watch { refreshSpeaker() })
        refreshLocal()
        refreshSubsonic()
        refreshDaap()
        refreshMA()
        refreshSpeaker()
    }

    override fun shown() = refreshLocal()

    // MARK: This device

    private fun refreshLocal() {
        val l = app.local
        localBox.removeAllViews()
        localBox.add(Ui.text(context, "${l.songs.size} songs${if (l.scanning) " · scanning…" else ""}", 15f))
        if (!l.hasPermission) {
            localBox.add(Ui.text(context, "Allow access to see the music already on this device.", 13f, Ui.DIM), top = 4)
            localBox.add(Ui.button(context, "Allow access to music", primary = true) { activity.requestStoragePermission() }, top = 6)
        }
        localBox.add(Ui.text(context, "App music folder (copy songs here over USB):\n${l.folder.path}", 12f, Ui.DIM), top = 8)
        localBox.add(Ui.horizontal(context).apply {
            add(Ui.button(context, "Import songs…") { activity.importSongs() }, 0, weight = 1f)
            add(Ui.button(context, "Rescan") { l.rescan() }, 0, weight = 1f, side = 4)
        }, top = 6)
    }

    // MARK: Subsonic

    private fun subsonicCard(): View {
        val s = app.subsonic
        val server = Ui.field(context, "Server address (https://music.example.com)", s.server, uri = true)
        val user = Ui.field(context, "Username", s.username)
        val pass = Ui.field(context, if (s.hasPassword) "Password (saved)" else "Password", password = true)
        val key = Ui.field(context, if (s.hasApiKey) "API key (saved)" else "API key (instead of a password)", password = true)
        return Ui.card(context).apply {
            add(server)
            add(user, top = 6)
            add(pass, top = 6)
            add(key, top = 6)
            add(Ui.horizontal(context).apply {
                add(Ui.button(context, "Connect", primary = true) {
                    s.configure(server.text.toString(), user.text.toString(), pass.text.toString(), key.text.toString())
                    pass.setText("")
                    key.setText("")
                }, 0, weight = 1f)
                add(Ui.button(context, "Sign out") { s.signOut() }, 0, weight = 1f, side = 4)
            }, top = 8)
            add(subsonicStatus, top = 6)
        }
    }

    private fun refreshSubsonic() {
        val st = app.subsonic.status
        subsonicStatus.text = when (st.state) {
            SubsonicClient.State.CONNECTED -> "Connected to ${st.serverType ?: "Subsonic"} ${st.serverVersion ?: ""} with ${st.auth ?: "password"}" +
                (if (st.openSubsonic) " (OpenSubsonic${if (st.apiKeySupported) ", API keys supported" else ""})" else "")
            SubsonicClient.State.CONNECTING -> "Connecting…"
            SubsonicClient.State.DISCONNECTED -> st.error ?: "Not connected"
        }
        subsonicStatus.setTextColor(if (st.error != null) Ui.ERROR else Ui.DIM)
    }

    // MARK: DAAP

    private fun refreshDaap() {
        val d = app.daap
        daapBox.removeAllViews()
        daapBox.add(Ui.check(context, "Find shared libraries on the network", d.discover) { d.discover = it })
        val open = d.openShare
        daapBox.add(Ui.text(context, when (d.state) {
            DaapClient.State.CONNECTED -> "Open: ${open?.second} (${d.songs.size} songs)"
            DaapClient.State.CONNECTING -> "Opening ${open?.second}…"
            DaapClient.State.DISCONNECTED -> d.error ?: if (d.discovering) "Looking for shared libraries…" else "No library open"
        }, 13f, if (d.error != null) Ui.ERROR else Ui.DIM), top = 6)
        for (share in d.shares) {
            val row = Ui.horizontal(context)
            val label = share.name + (if (share.needsPassword) " 🔒" else "") + if (share.discovered) "" else " (added)"
            row.add(Ui.button(context, label, primary = open?.first == share.id && d.state == DaapClient.State.CONNECTED) {
                if (share.needsPassword && !share.hasPassword) {
                    Ui.ask(context, share.name, "Password", password = true) { d.connect(share.id, it) }
                } else d.connect(share.id)
            }, 0, weight = 1f)
            row.add(Ui.button(context, "⋯") {
                val opts = arrayListOf("Enter password", "Forget password")
                if (share.address != null) opts.add("Remove")
                Ui.choose(context, share.name, opts) { i ->
                    when (opts[i]) {
                        "Enter password" -> Ui.ask(context, share.name, "Password", password = true) { d.connect(share.id, it) }
                        "Forget password" -> d.forgetPassword(share.id)
                        "Remove" -> d.removeServer(share.address!!)
                    }
                }
            }, ViewGroup.LayoutParams.WRAP_CONTENT, side = 4)
            daapBox.add(row, top = 6)
        }
        daapBox.add(Ui.horizontal(context).apply {
            add(Ui.button(context, "Add by address…") {
                Ui.ask(context, "Add a shared library", "host or host:3689") { a ->
                    d.addServer(a)
                    d.connect(a)
                }
            }, 0, weight = 1f)
            add(Ui.button(context, "Search again") { d.runDiscovery() }, 0, weight = 1f, side = 4)
        }, top = 8)
        if (d.state == DaapClient.State.CONNECTED) daapBox.add(Ui.button(context, "Close library") { d.disconnect() }, top = 6)
        daapBox.add(Ui.text(context, "iTunes 7+ and Music.app shares need a signature this app cannot make.", 12f, Ui.DIM), top = 6)
    }

    // MARK: Music Assistant

    private fun maCard(): View {
        val ma = app.musicAssistant
        val server = Ui.field(context, "Server (homeassistant.local:8095)", ma.server, uri = true)
        val user = Ui.field(context, "Username")
        val pass = Ui.field(context, "Password", password = true)
        val token = Ui.field(context, "Or paste a long-lived token", password = true)
        fun run(work: () -> Unit) = Bg.load(work) { r -> r.onFailure { Ui.toast(context, it.text) } }
        return Ui.card(context).apply {
            add(server)
            add(user, top = 6)
            add(pass, top = 6)
            add(Ui.horizontal(context).apply {
                add(Ui.button(context, "Sign in", primary = true) {
                    val s = server.text.toString()
                    val u = user.text.toString()
                    val p = pass.text.toString()
                    pass.setText("")
                    run { ma.login(s, u, p) }
                }, 0, weight = 1f)
                add(Ui.button(context, "Home Assistant…") { browserSignIn(server.text.toString()) }, 0, weight = 1f, side = 4)
            }, top = 8)
            add(token, top = 8)
            add(Ui.horizontal(context).apply {
                add(Ui.button(context, "Use token") {
                    val s = server.text.toString()
                    val t = token.text.toString()
                    token.setText("")
                    if (t.isNotBlank()) run { ma.useToken(s, t) }
                }, 0, weight = 1f)
                add(Ui.button(context, "Connect") {
                    val s = server.text.toString()
                    run {
                        ma.useServerOnly(s)
                    }
                }, 0, weight = 1f, side = 4)
                add(Ui.button(context, "Sign out") { ma.signOut() }, 0, weight = 1f, side = 4)
            }, top = 6)
            add(maStatus, top = 6)
            add(maPlayers, top = 4)
        }
    }

    private fun browserSignIn(server: String) {
        val ma = app.musicAssistant
        Bg.load({ ma.loginProviders(server) }) { r ->
            r.onFailure { Ui.toast(context, it.text) }.onSuccess { providers ->
                if (providers.isEmpty()) return@onSuccess Ui.toast(context, "This server offers no browser sign-in")
                Ui.choose(context, "Sign in with", providers.map { it.label }) { i ->
                    Bg.load({
                        ma.beginBrowserLogin(server, providers[i].id) { e ->
                            Ui.toast(context, e?.text ?: "Signed in to Music Assistant")
                        }
                    }) { u -> u.onSuccess { activity.openUrl(it) }.onFailure { Ui.toast(context, it.text) } }
                }
            }
        }
    }

    private fun refreshMA() {
        val ma = app.musicAssistant
        val st = ma.status
        maStatus.text = when (st.state) {
            MusicAssistantClient.State.CONNECTED -> "Connected to ${st.serverName ?: "Music Assistant"} ${st.serverVersion ?: ""}" +
                (st.user?.let { " as $it" } ?: "") + (if (ma.tokenKind.isNotEmpty()) " (${ma.tokenKind} token)" else "")
            MusicAssistantClient.State.CONNECTING -> "Connecting…"
            MusicAssistantClient.State.DISCONNECTED -> st.error ?: if (ma.hasToken) "Not connected" else "Not signed in"
        }
        maStatus.setTextColor(if (st.error != null) Ui.ERROR else Ui.DIM)
        maPlayers.removeAllViews()
        if (ma.players.isNotEmpty()) {
            maPlayers.add(Ui.text(context, "Library items play on:", 13f, Ui.DIM))
            val group = RadioGroup(context)
            for (p in ma.players) group.addView(RadioButton(context).apply {
                text = p.name + (p.state?.let { " · $it" } ?: "")
                setTextColor(Ui.TEXT)
                isChecked = p.id == ma.selectedPlayerID
                setOnClickListener { ma.selectedPlayerID = p.id }
            })
            maPlayers.add(group)
        }
    }

    // MARK: Speaker

    private fun refreshSpeaker() {
        val s = app.sendspin
        speakerBox.removeAllViews()
        speakerBox.add(Switch(context).apply {
            text = "Play Music Assistant on this device"
            setTextColor(Ui.TEXT)
            textSize = 16f
            isChecked = s.enabled
            setOnCheckedChangeListener { _, b ->
                s.name = speakerName.text.toString().trim()
                s.directAddress = speakerAddress.text.toString().trim()
                s.enabled = b
            }
        })
        speakerBox.add(Ui.text(context, when (s.connection) {
            SendspinClient.Connection.OFF -> "Off"
            SendspinClient.Connection.CONNECTING -> "Connecting…"
            SendspinClient.Connection.CONNECTED -> "Connected" + (if (s.paired) " (paired)" else " (not paired)") +
                (s.groupName?.let { " · group $it" } ?: "")
            SendspinClient.Connection.ERROR -> s.error ?: "Error"
        }, 13f, if (s.connection == SendspinClient.Connection.ERROR) Ui.ERROR else Ui.DIM), top = 4)

        s.pairingCode?.let {
            speakerBox.add(Ui.text(context, "Pairing PIN", 13f, Ui.DIM), top = 8)
            speakerBox.add(Ui.text(context, it.chunked(3).joinToString(" "), 34f, Ui.ACCENT, bold = true))
        }
        s.pairingMessage?.let { speakerBox.add(Ui.text(context, it, 13f, Ui.ACCENT), top = 4) }

        speakerBox.add(Ui.text(context, "Connect", 13f, Ui.DIM), top = 10)
        val group = RadioGroup(context)
        for (r in SendspinClient.Route.values()) group.addView(RadioButton(context).apply {
            text = r.title
            setTextColor(Ui.TEXT)
            isChecked = s.route == r
            setOnClickListener {
                s.directAddress = speakerAddress.text.toString().trim()
                s.route = r
            }
        })
        speakerBox.add(group)
        (speakerAddress.parent as? ViewGroup)?.removeView(speakerAddress)
        (speakerName.parent as? ViewGroup)?.removeView(speakerName)
        if (s.route == SendspinClient.Route.DIRECT) speakerBox.add(speakerAddress, top = 4)
        else speakerBox.add(Ui.text(context, "Uses the Music Assistant server and sign-in above (its /sendspin WebSocket).", 12f, Ui.DIM))
        speakerBox.add(speakerName, top = 6)

        speakerBox.add(Ui.button(context, "Format: ${s.codec.uppercase()}") {
            Ui.choose(context, "Preferred format (PCM is always offered)", SendspinClient.codecs.map { it.uppercase() }) { i ->
                s.codec = SendspinClient.codecs[i]
                s.restart()
            }
        }, top = 6)
        speakerBox.add(Ui.check(context, "Require pairing", s.requirePairing) {
            s.requirePairing = it
            s.restart()
        }, top = 4)
        speakerBox.add(Ui.horizontal(context).apply {
            add(Ui.button(context, "Apply") {
                s.name = speakerName.text.toString().trim()
                s.directAddress = speakerAddress.text.toString().trim()
                s.restart()
            }, 0, weight = 1f)
            add(Ui.button(context, "Allow pairing") { s.allowPairing() }, 0, weight = 1f, side = 4)
        }, top = 6)
        speakerBox.add(Ui.text(context, "Speaker volume (as Music Assistant sees it)", 13f, Ui.DIM), top = 8)
        speakerBox.add(Ui.seek(context, 100, s.volume) { v, done -> if (done) s.setVolume(v) })
        speakerBox.add(Ui.check(context, "Muted", s.muted) { if (it != s.muted) s.setMuted(it) })

        speakerBox.add(Ui.text(context, "Pairing code (enter it in a Sendspin server to pair without a PIN):", 12f, Ui.DIM), top = 10)
        val token = s.pairingToken
        speakerBox.add(Ui.text(context, token, 12f).apply { setTextIsSelectable(true) })
        speakerBox.add(Ui.horizontal(context).apply {
            add(Ui.button(context, "Copy code") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Sendspin pairing code", token))
                Ui.toast(context, "Copied")
            }, 0, weight = 1f)
            add(Ui.button(context, "Forget pairings") {
                Ui.dialog(context).setTitle("Forget every pairing?").setMessage("Servers will need to pair with this device again.")
                    .setPositiveButton("Forget") { _, _ -> s.forgetPairings() }.setNegativeButton("Cancel", null).show()
            }, 0, weight = 1f, side = 4)
        }, top = 4)
        if (s.pairedServers.isNotEmpty()) speakerBox.add(Ui.text(context, "Paired with ${s.pairedServers.size} server(s)", 12f, Ui.DIM), top = 4)
        speakerBox.add(Ui.text(context, "Client id: ${s.clientID}", 11f, Ui.DIM).apply { setTextIsSelectable(true) }, top = 4)
    }
}
