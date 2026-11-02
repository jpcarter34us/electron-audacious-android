package io.github.jpcarter34us.audacious.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.R
import io.github.jpcarter34us.audacious.engine.BlurScope
import io.github.jpcarter34us.audacious.engine.SpectrumAnalyzer
import io.github.jpcarter34us.audacious.engine.VUMeter
import io.github.jpcarter34us.audacious.engine.VisFrame
import io.github.jpcarter34us.audacious.engine.VisualizerKind
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.player.RepeatMode
import io.github.jpcarter34us.audacious.ui.Ui.add
import io.github.jpcarter34us.audacious.util.Images
import io.github.jpcarter34us.audacious.util.Prefs

/** Now Playing: cover, title, position, transport, volume and a
 *  visualization of what is being heard (the iOS port's NowPlayingView). */
class NowPlayingScreen(activity: MainActivity) : Screen(activity) {
    private val player = App.app.player
    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat())
    }
    private val vis = VisualizerView(context)
    private val title = Ui.text(context, "", 20f, bold = true, lines = 2).apply { gravity = Gravity.CENTER }
    private val subtitle = Ui.text(context, "", 15f, Ui.DIM, lines = 2).apply { gravity = Gravity.CENTER }
    private val status = Ui.text(context, "", 13f, Ui.ACCENT, lines = 2).apply { gravity = Gravity.CENTER }
    private val elapsed = Ui.text(context, "0:00", 12f, Ui.DIM)
    private val remaining = Ui.text(context, "--:--", 12f, Ui.DIM)
    private var seeking = false
    private val position = Ui.seek(context, 1000, 0) { p, done ->
        seeking = !done
        val d = player.duration ?: return@seek
        if (done) player.seek(d * p / 1000) else elapsed.text = Ui.time(d * p / 1000)
    }
    private val shuffle = Ui.icon(context, R.drawable.ic_shuffle, 44) { player.shuffle = !player.shuffle }
    private val repeat = Ui.icon(context, R.drawable.ic_repeat, 44) {
        player.repeatMode = RepeatMode.values()[(player.repeatMode.ordinal + 1) % RepeatMode.values().size]
    }
    private val playPause = Ui.icon(context, R.drawable.ic_play, 64, Ui.ACCENT) { player.togglePause() }
    private val volume = Ui.seek(context, 100, (player.volume * 100).toInt()) { p, _ -> player.volume = p / 100.0 }
    private var coverKey: String? = null

    override val view: View = Ui.vertical(context, 16).apply {
        val top = android.widget.FrameLayout(context)
        top.addView(cover)
        top.addView(vis)
        add(top, h = 0, weight = 1f)
        add(title, top = 12)
        add(subtitle, top = 2)
        add(status, top = 4)
        add(position, top = 8)
        add(Ui.horizontal(context).apply {
            add(elapsed, 0, weight = 1f)
            add(remaining.apply { gravity = Gravity.END }, 0, weight = 1f)
        })
        add(Ui.horizontal(context).apply {
            gravity = Gravity.CENTER
            addView(shuffle)
            add(Ui.icon(context, R.drawable.ic_previous, 52) { player.previous() }, Ui.dp(context, 52), Ui.dp(context, 52), side = 8)
            add(playPause, Ui.dp(context, 64), Ui.dp(context, 64))
            add(Ui.icon(context, R.drawable.ic_next, 52) { player.next() }, Ui.dp(context, 52), Ui.dp(context, 52), side = 8)
            addView(repeat)
        }, top = 4)
        add(Ui.horizontal(context).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_volume)
                setColorFilter(Ui.DIM)
            }, Ui.dp(context, 24), Ui.dp(context, 24))
            add(volume, 0, weight = 1f)
        }, top = 8)
    }

    init {
        // Tapping the picture cycles cover / spectrum / scope / VU.
        vis.setOnClickListener {
            val kinds = VisualizerKind.values()
            vis.kind = kinds[(vis.kind.ordinal + 1) % kinds.size]
            Prefs.set("ui.visualizer", vis.kind.name)
            Ui.toast(context, vis.kind.title)
            refresh()
        }
        watch(player.changed.watch { refresh() })
        watch(player.tick.watch { refreshTime() })
        refresh()
    }

    override fun shown() {
        refresh()
        vis.running = true
    }

    override fun hidden() {
        vis.running = false
    }

    private fun refresh() {
        val t = player.currentTrack
        val live = player.live
        title.text = t?.title ?: "Nothing playing"
        subtitle.text = when {
            live != null -> listOfNotNull(live.artist, live.album).joinToString(" · ").ifEmpty { live.source }
            t != null -> t.subtitle
            else -> "Choose songs in the Library"
        }
        status.text = when {
            player.lastError != null -> player.lastError
            player.isBuffering -> "Buffering…"
            live != null -> live.source
            else -> ""
        }
        status.setTextColor(if (player.lastError != null) Ui.ERROR else Ui.ACCENT)
        playPause.setImageResource(if (player.state == Player.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        shuffle.setColorFilter(if (player.shuffle) Ui.ACCENT else Ui.DIM)
        repeat.setImageResource(if (player.repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.setColorFilter(if (player.repeatMode == RepeatMode.OFF) Ui.DIM else Ui.ACCENT)
        shuffle.isEnabled = live == null
        position.isEnabled = live == null && player.duration != null && player.state != Player.State.STOPPED
        volume.progress = (player.volume * 100).toInt()
        vis.visibility = if (vis.kind == VisualizerKind.OFF) View.INVISIBLE else View.VISIBLE
        cover.alpha = if (vis.kind == VisualizerKind.OFF) 1f else 0.25f
        val key = t?.let { it.id + (it.artworkKey ?: "") }
        if (key != coverKey) {
            coverKey = key
            if (t == null) {
                cover.scaleType = ImageView.ScaleType.CENTER
                cover.setImageResource(R.drawable.ic_music)
            } else Images.into(cover, key!!, { App.app.artwork(t) }, R.drawable.ic_music)
        }
        refreshTime()
    }

    private fun refreshTime() {
        if (seeking) return
        val d = player.duration
        elapsed.text = Ui.time(player.time)
        remaining.text = if (d != null) "-" + Ui.time(maxOf(0.0, d - player.time)) else "--:--"
        position.progress = if (d != null && d > 0) (player.time / d * 1000).toInt() else 0
    }
}

/** Spectrum Analyzer, Blur Scope and VU Meter, drawn at about 30 fps from
 *  the output's visualization tap. */
class VisualizerView(context: Context) : View(context) {
    var kind: VisualizerKind = try {
        VisualizerKind.valueOf(Prefs.string("ui.visualizer", VisualizerKind.SPECTRUM.name))
    } catch (_: Exception) {
        VisualizerKind.SPECTRUM
    }
    var running = false
        set(v) {
            field = v
            if (v) postInvalidate()
        }
    private val frame = VisFrame()
    private val spectrum = SpectrumAnalyzer()
    private val scope = BlurScope()
    private val vu = VUMeter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT }
    private val scopeBitmap = Bitmap.createBitmap(scope.width, scope.height, Bitmap.Config.ARGB_8888)
    private val colors = IntArray(scope.width * scope.height)
    private val dst = Rect()

    override fun onDraw(canvas: Canvas) {
        if (!running || kind == VisualizerKind.OFF) return
        frame.capture()
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            VisualizerKind.SPECTRUM -> {
                val bands = maxOf(12, (w / Ui.dp(context, 10)).toInt())
                spectrum.render(frame, bands)
                val bw = w / spectrum.bars.size
                for ((i, v) in spectrum.bars.withIndex()) {
                    val bh = v / SpectrumAnalyzer.RANGE * h
                    canvas.drawRect(i * bw + 1, h - bh, (i + 1) * bw - 1, h, paint)
                }
            }
            VisualizerKind.SCOPE -> {
                scope.render(frame)
                val r = Ui.ACCENT shr 16 and 0xFF
                val g = Ui.ACCENT shr 8 and 0xFF
                val b = Ui.ACCENT and 0xFF
                for (i in colors.indices) {
                    val a = scope.pixels[i]
                    colors[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                scopeBitmap.setPixels(colors, 0, scope.width, 0, 0, scope.width, scope.height)
                dst.set(0, 0, width, height)
                canvas.drawBitmap(scopeBitmap, null, dst, null)
            }
            VisualizerKind.VU -> {
                vu.render(frame)
                val n = vu.level.size
                val gap = Ui.dp(context, 8)
                val barH = minOf(Ui.dp(context, 28).toFloat(), (h - gap * (n + 1)) / maxOf(1, n))
                val top0 = (h - n * barH - (n - 1) * gap) / 2
                for (c in 0 until n) {
                    val top = top0 + c * (barH + gap)
                    val level = (vu.level[c] + VUMeter.RANGE) / VUMeter.RANGE * w
                    val peak = (vu.peak[c] + VUMeter.RANGE) / VUMeter.RANGE * w
                    canvas.drawRect(0f, top, level, top + barH, paint)
                    canvas.drawRect(maxOf(0f, peak - 4), top, peak, top + barH, paint)
                }
            }
            VisualizerKind.OFF -> {}
        }
        postInvalidateDelayed(33)
    }
}
