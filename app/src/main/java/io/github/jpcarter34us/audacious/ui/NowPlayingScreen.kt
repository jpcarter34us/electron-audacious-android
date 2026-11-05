package io.github.jpcarter34us.audacious.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
        add(FrameLayout(context).apply {
            addView(Ui.icon(context, R.drawable.ic_waveform, 44, Ui.ACCENT) { chooseVisualization() }.apply {
                contentDescription = "Visualization"
            }, FrameLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(Ui.text(context, "Now Playing", 17f, bold = true),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
        val top = FrameLayout(context)
        top.addView(cover)
        // The visualization along the bottom of the cover, as in Audacious's
        // main window.
        top.addView(vis, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 96), Gravity.BOTTOM))
        add(top, h = 0, weight = 1f, top = 4)
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
        watch(player.changed.watch { refresh() })
        watch(player.tick.watch { refreshTime() })
        refresh()
    }

    override fun shown() {
        vis.shown = true
        refresh()
    }

    override fun hidden() {
        vis.shown = false
    }

    private fun chooseVisualization() {
        val kinds = VisualizerKind.values()
        var dialog: android.app.AlertDialog? = null
        dialog = Ui.dialog(context).setTitle("Visualization")
            .setSingleChoiceItems(kinds.map { it.title }.toTypedArray(), vis.kind.ordinal) { _, i ->
                vis.kind = kinds[i]
                Prefs.set("ui.visualizer", vis.kind.name)
                refresh()
                dialog?.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
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
        vis.visibility = if (vis.kind == VisualizerKind.OFF) View.GONE else View.VISIBLE
        vis.state = player.state
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

/** Spectrum Analyzer, Blur Scope and VU Meter, drawn about 30 times a second
 *  from the output's visualization tap (the iOS port's VisualizerView): it
 *  holds its last frame while paused and clears when playback stops. */
class VisualizerView(context: Context) : View(context) {
    var kind: VisualizerKind = try {
        VisualizerKind.valueOf(Prefs.string("ui.visualizer", VisualizerKind.SPECTRUM.name))
    } catch (_: Exception) {
        VisualizerKind.SPECTRUM
    }
        set(v) {
            field = v
            update()
        }
    /** The screen is on show. */
    var shown = false
        set(v) {
            field = v
            update()
        }
    var state = Player.State.STOPPED
        set(v) {
            if (v == Player.State.STOPPED && field != v) clear()
            field = v
            update()
        }
    private val frame = VisFrame()
    private val spectrum = SpectrumAnalyzer()
    private val scope = BlurScope()
    private val vu = VUMeter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint().apply { color = 0x14FFFFFF }
    private val scopeBitmap = Bitmap.createBitmap(scope.width, scope.height, Bitmap.Config.ARGB_8888)
    private val colors = IntArray(scope.width * scope.height)
    private val dst = Rect()
    private var lastStep = 0L
    private val tick = Runnable { invalidate() }

    private val animating get() = shown && state == Player.State.PLAYING && kind != VisualizerKind.OFF

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xBF000000.toInt()))
    }

    private fun update() {
        removeCallbacks(tick)
        invalidate()
    }

    private fun clear() {
        spectrum.clear()
        scope.clear()
        vu.clear()
    }

    /** One frame per tick: the system also redraws for layout. */
    private fun step() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStep < 25) return
        lastStep = now
        frame.capture()
        when (kind) {
            VisualizerKind.SPECTRUM -> spectrum.render(frame, width / Ui.dp(context, 10))
            VisualizerKind.SCOPE -> scope.render(frame)
            VisualizerKind.VU -> vu.render(frame)
            VisualizerKind.OFF -> {}
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        spectrumShader = LinearGradient(0f, h.toFloat(), 0f, 0f, Ui.ACCENT, 0xFFE0F0FF.toInt(), Shader.TileMode.CLAMP)
        val inset = Ui.dp(context, 8).toFloat()
        vuShader = LinearGradient(inset, 0f, w - inset, 0f, intArrayOf(0xFF34C759.toInt(), 0xFFFFD60A.toInt(), 0xFFFF3B30.toInt()),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }

    private var spectrumShader: Shader? = null
    private var vuShader: Shader? = null

    override fun onDraw(canvas: Canvas) {
        if (kind == VisualizerKind.OFF) return
        if (animating) step()
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            VisualizerKind.SPECTRUM -> {
                val bars = spectrum.bars
                if (bars.isNotEmpty()) {
                    paint.shader = spectrumShader
                    val inset = Ui.dp(context, 4)
                    val bw = w / bars.size
                    for ((i, v) in bars.withIndex()) {
                        if (v <= 0) continue
                        val bh = v / SpectrumAnalyzer.RANGE * (h - 2 * inset)
                        canvas.drawRect(i * bw + 1, h - inset - bh, (i + 1) * bw - 1, h - inset, paint)
                    }
                }
            }
            VisualizerKind.SCOPE -> {
                val r = Ui.ACCENT shr 16 and 0xFF
                val g = Ui.ACCENT shr 8 and 0xFF
                val b = Ui.ACCENT and 0xFF
                for (i in colors.indices) colors[i] = (scope.pixels[i] shl 24) or (r shl 16) or (g shl 8) or b
                scopeBitmap.setPixels(colors, 0, scope.width, 0, 0, scope.width, scope.height)
                dst.set(0, 0, width, height)
                canvas.drawBitmap(scopeBitmap, null, dst, null)
            }
            VisualizerKind.VU -> {
                val n = vu.level.size
                if (n > 0) {
                    paint.shader = vuShader
                    val inset = Ui.dp(context, 8).toFloat()
                    val full = w - 2 * inset
                    val gap = maxOf(Ui.dp(context, 4).toFloat(), h * 0.08f)
                    val barH = (h - gap * (n + 1)) / n
                    for (c in 0 until n) {
                        val top = gap + c * (barH + gap)
                        val level = (vu.level[c] + VUMeter.RANGE) / VUMeter.RANGE * full
                        val peak = (vu.peak[c] + VUMeter.RANGE) / VUMeter.RANGE * full
                        canvas.drawRect(inset, top, inset + full, top + barH, track)
                        canvas.drawRect(inset, top, inset + level, top + barH, paint)
                        canvas.drawRect(inset + maxOf(0f, peak - Ui.dp(context, 2)), top, inset + peak, top + barH, paint)
                    }
                }
            }
            VisualizerKind.OFF -> {}
        }
        if (animating) {
            removeCallbacks(tick)
            postDelayed(tick, 33)
        }
    }
}
