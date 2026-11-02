package io.github.jpcarter34us.audacious.engine

import android.content.Context
import io.github.jpcarter34us.audacious.util.Json
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.bool
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.objects
import io.github.jpcarter34us.audacious.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** libaudcore's configuration (equalizer, effect settings, which effects are
 *  on), saved in upstream's config file layout. */
object AudConfig {
    private lateinit var file: File
    private var loaded = false

    /** Loads the saved configuration and starts the plugins. Call before
     *  anything else uses the native core. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        file = File(context.filesDir, "config")
        Native.init(file.path)
    }

    private val saver = Runnable { Native.configSave(file.path) }

    /** Saves shortly, coalescing slider drags. */
    fun saveSoon() {
        Main.cancel(saver)
        Main.delay(500, saver)
    }

    fun saveNow() {
        Main.cancel(saver)
        Native.configSave(file.path)
    }
}

/** The Effect plugins (Settings › Plugins › Effect in Audacious). */
class EffectsModel {
    class Effect(val id: String, val index: Int, val name: String, val about: String?, val hasSettings: Boolean,
                 val preservesFormat: Boolean, var enabled: Boolean)

    val changed = Signal()

    val effects: List<Effect> = (0 until Native.effectCount()).map { i ->
        Effect(Native.effectId(i), i, Native.effectName(i), Native.effectAbout(i), Native.effectHasPrefs(i),
            Native.effectPreservesFormat(i), Native.effectEnabled(i))
    }

    fun setEnabled(effect: Effect, enabled: Boolean) {
        Native.effectSetEnabled(effect.index, enabled)
        effect.enabled = enabled
        AudConfig.saveSoon()
        changed.emit()
    }

    fun settings(effect: Effect): PluginSettings? = PluginSettings.open(effect.index)
}

/** One PreferencesWidget, as AudCore serializes it (the shape of
 *  ElectronPlayer's PrefWidget in src/shared/prefs.ts). */
class PrefWidget(o: JSONObject) {
    val id = o.int("id") ?: -1
    val type = o.str("type") ?: ""
    val label: String? = o.str("label")
    val child = o.bool("child")
    val cfg: String? = o.str("cfg")
    val value: Int? = o.int("value")
    val min: Double? = o.double("min")
    val max: Double? = o.double("max")
    val step: Double? = o.double("step")
    val unit: String? = o.str("unit")
    val password = o.bool("password")
    val items: List<Pair<String, Any>> = o.objects("items").map { (it.str("label") ?: "") to (it.opt("value") ?: "") }
    val horizontal = o.bool("horizontal")
    val frame = o.bool("frame")
    val children: List<PrefWidget> = o.objects("children").map { PrefWidget(it) }
    val tabs: List<Pair<String, List<PrefWidget>>> = o.objects("tabs").map { t -> (t.str("name") ?: "") to t.objects("children").map { PrefWidget(it) } }

    /** The label without GTK mnemonics ("_Delay") and markup ("<b>Echo</b>"). */
    val plainLabel: String
        get() {
            var text = label ?: return ""
            for (tag in listOf("<b>", "</b>", "<i>", "</i>")) text = text.replace(tag, "")
            text = text.replace("__", "\u0001").replace("_", "").replace("\u0001", "_")
            return text.removeSuffix(":")
        }

    /** Labels written as <b>...</b> are section headings. */
    val isHeading get() = type == "label" && label?.startsWith("<b>") == true
}

/** A plugin's settings, generated from its PreferencesWidget declarations
 *  (libaudcore/preferences.h), the way ElectronPlayer's prefs-form.ts does. */
class PluginSettings private constructor(private val effect: Int, val name: String, val hasApply: Boolean,
                                         val widgets: List<PrefWidget>) {
    companion object {
        fun open(effect: Int): PluginSettings? {
            val raw = Native.prefsOpen(effect) ?: return null
            val o = Json.parseObject(raw) ?: run {
                Native.prefsClose(effect)
                return null
            }
            val widgets = (o.opt("widgets") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                ?.map { PrefWidget(it) } ?: emptyList()
            return PluginSettings(effect, o.str("name") ?: "", o.bool("hasApply"), widgets).also { it.reload() }
        }
    }

    val changed = Signal()
    /** Widget id to its value (Boolean, Number or String). */
    var values: Map<Int, Any> = emptyMap()
        private set
    private var closed = false

    fun set(widget: PrefWidget, value: Any) {
        val text = when (value) {
            is Boolean -> if (value) "true" else "false"
            is Double -> if (value == Math.floor(value)) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
        Native.prefsSet(effect, widget.id, text)
        reload()
        AudConfig.saveSoon()
    }

    fun press(widget: PrefWidget) {
        Native.prefsButton(effect, widget.id)
        reload()
    }

    fun apply() {
        Native.prefsApply(effect)
        reload()
        AudConfig.saveSoon()
    }

    fun close() {
        if (closed) return
        closed = true
        Native.prefsClose(effect)
        AudConfig.saveSoon()
    }

    private fun reload() {
        val o = Native.prefsValues(effect)?.let { Json.parseObject(it) } ?: return
        val out = HashMap<Int, Any>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = k.toIntOrNull() ?: continue
            o.opt(k)?.takeIf { it != JSONObject.NULL }?.let { out[id] = it }
        }
        values = out
        changed.emit()
    }

    fun bool(id: Int): Boolean = Json.bool(values[id])
    fun number(id: Int): Double = Json.double(values[id]) ?: (if (Json.bool(values[id])) 1.0 else 0.0)
    fun string(id: Int): String = Json.str(values[id]) ?: ""
}

class EqPreset(val name: String, val preamp: Double, val bands: DoubleArray) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("preamp", preamp).put("bands", JSONArray().also { a -> bands.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): EqPreset? {
            val b = o.optJSONArray("bands") ?: return null
            return EqPreset(o.str("name") ?: return null, o.double("preamp") ?: 0.0, DoubleArray(Native.EQ_BANDS) { b.optDouble(it, 0.0) })
        }

        private fun p(name: String, preamp: Double, vararg b: Int) = EqPreset(name, preamp, DoubleArray(b.size) { b[it].toDouble() })

        /** Starting points in the spirit of the classic Winamp presets
         *  (Audacious ships none of its own), as the iOS port. */
        val builtIn = listOf(
            p("Flat", 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            p("Bass Boost", -4.0, 7, 6, 5, 3, 1, 0, 0, 0, 0, 0),
            p("Treble Boost", -4.0, 0, 0, 0, 0, 0, 1, 3, 5, 6, 7),
            p("Classical", 0.0, 0, 0, 0, 0, 0, 0, -4, -4, -4, -6),
            p("Club", 0.0, 0, 0, 2, 3, 3, 3, 2, 0, 0, 0),
            p("Dance", -2.0, 6, 4, 1, 0, 0, -3, -4, -4, 0, 0),
            p("Pop", 0.0, -1, 2, 3, 4, 3, 0, -1, -1, -1, -1),
            p("Rock", -2.0, 5, 3, -3, -5, -2, 2, 5, 6, 6, 6),
            p("Vocal", -2.0, -2, -3, -3, 1, 4, 4, 3, 1, 0, -2),
            p("Headphones", -3.0, 3, 6, 3, -2, -1, 1, 3, 6, 7, 8),
            p("Loudness", -4.0, 6, 5, 0, 0, -2, 0, -1, -5, 5, 1),
        )
    }
}

/** The 10-band equalizer (libaudcore's equalizer.cc), with presets. */
class EqualizerModel(context: Context) {
    companion object {
        val bandLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }

    val changed = Signal()
    private val presetsFile = File(context.filesDir, "eq-presets.json")

    var active: Boolean = Native.eqActive()
        set(v) {
            field = v
            Native.eqSetActive(v)
            AudConfig.saveSoon()
            changed.emit()
        }

    var preamp: Double = Native.eqPreamp()
        set(v) {
            field = v
            Native.eqSetPreamp(v)
            AudConfig.saveSoon()
            changed.emit()
        }

    var bands: DoubleArray = Native.eqBands()
        private set

    fun setBand(i: Int, db: Double) {
        bands = bands.copyOf().also { it[i] = db }
        Native.eqSetBands(bands)
        AudConfig.saveSoon()
        changed.emit()
    }

    var userPresets: List<EqPreset> = try {
        val a = JSONArray(presetsFile.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> EqPreset.fromJson(o) } }
    } catch (_: Exception) {
        emptyList()
    }
        private set

    fun apply(preset: EqPreset) {
        preamp = preset.preamp
        bands = preset.bands.copyOf()
        Native.eqSetBands(bands)
        AudConfig.saveSoon()
        changed.emit()
    }

    fun reset() = apply(EqPreset("", 0.0, DoubleArray(Native.EQ_BANDS)))

    fun savePreset(name: String) {
        userPresets = (userPresets.filter { it.name != name } + EqPreset(name, preamp, bands.copyOf()))
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        persist()
    }

    fun deletePreset(preset: EqPreset) {
        userPresets = userPresets.filter { it.name != preset.name }
        persist()
    }

    private fun persist() {
        presetsFile.writeText(JSONArray().also { a -> userPresets.forEach { a.put(it.toJson()) } }.toString())
        changed.emit()
    }
}

/** One visualization frame, as libaudcore's vis_send_audio() hands its
 *  visualizers: 512 mono samples, their spectrum and per-channel peaks. */
class VisFrame {
    val pcm = FloatArray(Native.VIS_PCM)
    val freq = FloatArray(Native.VIS_FREQ)
    var levels = FloatArray(0)

    /** What is being heard now (taken by the output callback). */
    fun capture() {
        val peaks = FloatArray(8)
        val n = Native.visSnapshot(pcm, peaks)
        levels = peaks.copyOf(n)
        Native.calcFreq(pcm, freq)
    }
}
