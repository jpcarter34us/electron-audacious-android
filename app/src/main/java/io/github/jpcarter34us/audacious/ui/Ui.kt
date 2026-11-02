package io.github.jpcarter34us.audacious.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Colours and small view builders shared by the screens (no layouts in
 *  XML, no support libraries: every Android version from 4.1 draws these). */
object Ui {
    const val BG = 0xFF121212.toInt()
    const val SURFACE = 0xFF1E1E1E.toInt()
    const val SURFACE2 = 0xFF2A2A2A.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val DIM = 0xFFA8A8A8.toInt()
    const val ACCENT = 0xFFFF8A3D.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun pressable(c: Context, color: Int, pressed: Int = SURFACE2, radius: Int = 8) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, dp(c, radius).toFloat()))
        addState(intArrayOf(), rounded(color, dp(c, radius).toFloat()))
    }

    fun text(c: Context, s: CharSequence = "", size: Float = 15f, color: Int = TEXT, bold: Boolean = false, lines: Int = 0) =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    fun heading(c: Context, s: String) = text(c, s.uppercase(), 13f, ACCENT, bold = true).apply {
        setPadding(dp(c, 16), dp(c, 20), dp(c, 16), dp(c, 6))
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.BLACK else TEXT)
        background = pressable(c, if (primary) ACCENT else SURFACE2, if (primary) 0xFFFFB07A.toInt() else 0xFF3A3A3A.toInt())
        minHeight = dp(c, 44)
        setPadding(dp(c, 16), 0, dp(c, 16), 0)
        setOnClickListener { onClick() }
    }

    fun icon(c: Context, res: Int, size: Int = 48, tint: Int = TEXT, onClick: () -> Unit) = ImageButton(c).apply {
        setImageResource(res)
        setColorFilter(tint)
        background = pressable(c, Color.TRANSPARENT, SURFACE2, size / 2)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val p = dp(c, size / 5)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
        setOnClickListener { onClick() }
    }

    fun field(c: Context, hint: String, value: String = "", password: Boolean = false, uri: Boolean = false) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(0xFF777777.toInt())
        setTextColor(TEXT)
        textSize = 15f
        setSingleLine()
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        background = rounded(SURFACE2, dp(c, 8).toFloat())
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
    }

    fun check(c: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(c).apply {
        text = label
        setTextColor(TEXT)
        textSize = 15f
        isChecked = checked
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    fun vertical(c: Context, padding: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, padding)
        setPadding(p, p, p, p)
    }

    fun horizontal(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun scroll(c: Context, content: View) = ScrollView(c).apply {
        isFillViewport = true
        addView(content)
    }

    /** A card: a rounded surface holding a column of views. */
    fun card(c: Context) = vertical(c, 12).apply { background = rounded(SURFACE, dp(c, 12).toFloat()) }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                         weight: Float = 0f, top: Int = 0, side: Int = 0): View {
        val l = LinearLayout.LayoutParams(w, h, weight)
        l.topMargin = dp(context, top)
        l.leftMargin = dp(context, side)
        l.rightMargin = dp(context, side)
        addView(v, l)
        return v
    }

    fun seek(c: Context, max: Int, value: Int, onChange: (Int, Boolean) -> Unit) = SeekBar(c).apply {
        this.max = max
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p, false)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress, true)
        })
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

    fun dialog(c: Context) = AlertDialog.Builder(c, if (android.os.Build.VERSION.SDK_INT >= 21)
        android.R.style.Theme_Material_Dialog_Alert else @Suppress("DEPRECATION") AlertDialog.THEME_HOLO_DARK)

    fun choose(c: Context, title: String, items: List<String>, pick: (Int) -> Unit) {
        dialog(c).setTitle(title).setItems(items.toTypedArray()) { _, i -> pick(i) }.setNegativeButton("Cancel", null).show()
    }

    fun ask(c: Context, title: String, hint: String, value: String = "", password: Boolean = false, done: (String) -> Unit) {
        val f = field(c, hint, value, password)
        val box = vertical(c, 16).apply { addView(f) }
        dialog(c).setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> done(f.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    fun time(seconds: Double?): String {
        if (seconds == null || seconds.isNaN() || seconds < 0) return "--:--"
        val s = seconds.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/** One tab's content. */
abstract class Screen(val activity: MainActivity) {
    val context: Context get() = activity
    abstract val view: View
    private val unwatch = ArrayList<() -> Unit>()

    protected fun watch(remove: () -> Unit) {
        unwatch.add(remove)
    }

    open fun shown() {}
    open fun hidden() {}
    /** True if it handled Back (went up a level). */
    open fun back(): Boolean = false

    fun destroy() = unwatch.forEach { it() }
}
