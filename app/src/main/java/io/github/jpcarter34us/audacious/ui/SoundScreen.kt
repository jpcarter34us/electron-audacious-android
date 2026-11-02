package io.github.jpcarter34us.audacious.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.engine.EffectsModel
import io.github.jpcarter34us.audacious.engine.EqPreset
import io.github.jpcarter34us.audacious.engine.EqualizerModel
import io.github.jpcarter34us.audacious.engine.Native
import io.github.jpcarter34us.audacious.engine.PluginSettings
import io.github.jpcarter34us.audacious.engine.PrefWidget
import io.github.jpcarter34us.audacious.ui.Ui.add

/** Equalizer and Effects (the iOS port's SoundView): Audacious's 10-band
 *  equalizer and effect plugins, with each plugin's settings. */
class SoundScreen(activity: MainActivity) : Screen(activity) {
    private val eq = App.app.equalizer
    private val effects = App.app.effects
    private val max = Native.EQ_MAX_GAIN
    private val steps = (max * 2 * 10).toInt() // 0.1 dB
    private val eqSwitch = Switch(context).apply {
        text = "Equalizer"
        setTextColor(Ui.TEXT)
        textSize = 17f
        isChecked = eq.active
        setOnCheckedChangeListener { _, b -> eq.active = b }
    }
    private val sliders = ArrayList<Pair<SeekBar, TextView>>()
    private val effectsBox = Ui.vertical(context)

    override val view: View = Ui.scroll(context, Ui.vertical(context, 12).apply {
        add(Ui.text(context, "Sound", 22f, bold = true))
        add(Ui.card(context).apply {
            add(Ui.horizontal(context).apply {
                add(eqSwitch, 0, weight = 1f)
                add(Ui.button(context, "Presets") { presets() }, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            add(slider("Preamp", eq.preamp) { eq.preamp = it }, top = 8)
            for (i in 0 until Native.EQ_BANDS) add(slider(EqualizerModel.bandLabels[i] + " Hz", eq.bands[i]) { eq.setBand(i, it) })
        }, top = 8)
        add(Ui.heading(context, "Effects"))
        add(effectsBox)
        add(Ui.text(context, "Effects run in order, before the equalizer, as in Audacious. Ported from audacious-plugins.", 12f, Ui.DIM), top = 8)
    })

    init {
        buildEffects()
        watch(eq.changed.watch { refreshEq() })
    }

    private fun slider(label: String, value: Double, set: (Double) -> Unit): View {
        val valueText = Ui.text(context, "", 13f, Ui.DIM).apply { minWidth = Ui.dp(context, 64) }
        fun show(db: Double) {
            valueText.text = "%+.1f dB".format(db)
        }
        val bar = Ui.seek(context, steps, ((value + max) * 10).toInt()) { p, _ ->
            val db = p / 10.0 - max
            show(db)
            set(db)
        }
        show(value)
        sliders.add(bar to valueText)
        return Ui.horizontal(context).apply {
            add(Ui.text(context, label, 13f).apply { minWidth = Ui.dp(context, 64) }, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(bar, 0, weight = 1f)
            add(valueText, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun refreshEq() {
        eqSwitch.isChecked = eq.active
        val values = listOf(eq.preamp) + eq.bands.toList()
        for ((i, s) in sliders.withIndex()) {
            val v = values[i]
            s.first.progress = ((v + max) * 10).toInt()
            s.second.text = "%+.1f dB".format(v)
        }
    }

    private fun presets() {
        val all = EqPreset.builtIn + eq.userPresets
        val names = all.map { it.name } + listOf("Save current as…", "Delete a saved preset…", "Reset")
        Ui.choose(context, "Equalizer presets", names) { i ->
            when {
                i < all.size -> eq.apply(all[i])
                i == all.size -> Ui.ask(context, "Save preset", "Name") { n -> if (n.isNotBlank()) eq.savePreset(n.trim()) }
                i == all.size + 1 -> if (eq.userPresets.isEmpty()) Ui.toast(context, "No saved presets") else
                    Ui.choose(context, "Delete preset", eq.userPresets.map { it.name }) { eq.deletePreset(eq.userPresets[it]) }
                else -> eq.reset()
            }
        }
    }

    private fun buildEffects() {
        effectsBox.removeAllViews()
        for (e in effects.effects) {
            val card = Ui.card(context)
            card.add(Ui.horizontal(context).apply {
                add(Switch(context).apply {
                    text = e.name
                    setTextColor(Ui.TEXT)
                    textSize = 16f
                    isChecked = e.enabled
                    setOnCheckedChangeListener { _, b -> effects.setEnabled(e, b) }
                }, 0, weight = 1f)
                if (e.hasSettings) add(Ui.button(context, "Settings") { openSettings(e) }, ViewGroup.LayoutParams.WRAP_CONTENT, side = 4)
            })
            effectsBox.add(card, top = 8)
        }
    }

    private fun openSettings(e: EffectsModel.Effect) {
        val s = effects.settings(e) ?: return Ui.toast(context, "${e.name} has no settings")
        PluginSettingsForm(activity, s, e.about).show()
    }
}

/** A plugin's settings form, built from its PreferencesWidget list (the
 *  iOS port's PluginSettingsView, ElectronPlayer's prefs-form.ts). */
class PluginSettingsForm(private val activity: MainActivity, private val settings: PluginSettings, private val about: String?) {
    private val context get() = activity
    private val refreshers = ArrayList<() -> Unit>()

    fun show() {
        val box = Ui.vertical(context, 16)
        build(box, settings.widgets)
        about?.takeIf { it.isNotBlank() }?.let { box.add(Ui.text(context, it.trim(), 12f, Ui.DIM), top = 16) }
        val unwatch = settings.changed.watch { refreshers.forEach { it() } }
        val d = Ui.dialog(context).setTitle(settings.name).setView(Ui.scroll(context, box))
            .setPositiveButton("Done", null)
        if (settings.hasApply) d.setNeutralButton("Apply") { _, _ -> settings.apply() }
        val dialog = d.create()
        dialog.setOnDismissListener {
            unwatch()
            settings.close()
        }
        dialog.show()
    }

    private fun build(box: LinearLayout, widgets: List<PrefWidget>) {
        for (w in widgets) {
            when (w.type) {
                "label" -> box.add(if (w.isHeading) Ui.heading(context, w.plainLabel).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
                else Ui.text(context, w.plainLabel, 14f, Ui.DIM), top = 4)
                "button" -> box.add(Ui.button(context, w.plainLabel) { settings.press(w) }, top = 6)
                "check" -> {
                    val c = Ui.check(context, w.plainLabel, settings.bool(w.id)) { b -> if (b != settings.bool(w.id)) settings.set(w, b) }
                    refreshers.add { c.isChecked = settings.bool(w.id) }
                    box.add(c, top = 2)
                }
                "radio" -> {
                    val r = RadioButton(context).apply {
                        text = w.plainLabel
                        setTextColor(Ui.TEXT)
                        isChecked = settings.number(w.id).toInt() == w.value
                        setOnClickListener { settings.set(w, (w.value ?: 0).toDouble()) }
                    }
                    refreshers.add { r.isChecked = settings.number(w.id).toInt() == w.value }
                    box.add(r)
                }
                "spin" -> box.add(spin(w), top = 6)
                "entry", "file", "font" -> {
                    val f = Ui.field(context, w.plainLabel, settings.string(w.id), password = w.password)
                    f.setOnFocusChangeListener { _, has -> if (!has && f.text.toString() != settings.string(w.id)) settings.set(w, f.text.toString()) }
                    box.add(Ui.text(context, w.plainLabel, 13f, Ui.DIM), top = 6)
                    box.add(f)
                }
                "combo" -> {
                    val b = Ui.button(context, "") {
                        Ui.choose(context, w.plainLabel, w.items.map { it.first }) { i -> settings.set(w, w.items[i].second) }
                    }
                    fun label() {
                        val cur = settings.values[w.id]?.toString()
                        val match = w.items.firstOrNull { it.second.toString() == cur || (it.second as? Number)?.toDouble() == cur?.toDoubleOrNull() }
                        b.text = "${w.plainLabel}: ${match?.first ?: cur ?: ""}"
                    }
                    label()
                    refreshers.add { label() }
                    box.add(b, top = 6)
                }
                "box", "table" -> {
                    val inner = Ui.vertical(context)
                    if (w.frame) inner.background = Ui.rounded(Ui.SURFACE2, Ui.dp(context, 8).toFloat())
                    if (w.frame) inner.setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 8))
                    if (w.label != null) box.add(Ui.text(context, w.plainLabel, 14f, bold = true), top = 8)
                    build(inner, w.children)
                    box.add(inner, top = 4)
                }
                "notebook" -> for ((name, children) in w.tabs) {
                    box.add(Ui.heading(context, name).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
                    build(box, children)
                }
                "separator" -> box.add(View(context).apply { setBackgroundColor(Ui.SURFACE2) }, h = Ui.dp(context, 1), top = 8)
            }
        }
    }

    private fun spin(w: PrefWidget): View {
        val min = w.min ?: 0.0
        val max = w.max ?: 100.0
        val step = (w.step ?: 1.0).takeIf { it > 0 } ?: 1.0
        val count = Math.round((max - min) / step).toInt().coerceAtLeast(1)
        val label = Ui.text(context, "", 13f)
        fun show(v: Double) {
            val num = if (step >= 1 && v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
            label.text = "${w.plainLabel}: $num ${w.unit?.replace("_", "") ?: ""}".trim()
        }
        val bar = Ui.seek(context, count, Math.round((settings.number(w.id) - min) / step).toInt()) { p, done ->
            val v = min + p * step
            show(v)
            if (done) settings.set(w, v)
        }
        show(settings.number(w.id))
        refreshers.add {
            bar.progress = Math.round((settings.number(w.id) - min) / step).toInt()
            show(settings.number(w.id))
        }
        return Ui.vertical(context).apply {
            add(label)
            add(bar)
        }
    }
}
