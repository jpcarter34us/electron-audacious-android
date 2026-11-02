package io.github.jpcarter34us.audacious.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.PlaybackService
import io.github.jpcarter34us.audacious.R
import io.github.jpcarter34us.audacious.ui.Ui.add
import io.github.jpcarter34us.audacious.util.Prefs

/** The app's one window: five tabs (Now Playing, Playlist, Library, Sound,
 *  Settings), as the iOS port's RootView. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private val tabs = ArrayList<Pair<LinearLayout, () -> Screen>>()
    private val screens = HashMap<Int, Screen>()
    private var current = -1
    private val app get() = App.app

    companion object {
        const val REQUEST_IMPORT = 10
        const val REQUEST_PERMISSION = 11
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.BG)
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Ui.BG
            window.navigationBarColor = Ui.SURFACE
        }
        val root = Ui.vertical(this)
        root.setBackgroundColor(Ui.BG)
        content = FrameLayout(this)
        root.add(content, h = 0, weight = 1f)
        val bar = Ui.horizontal(this).apply { setBackgroundColor(Ui.SURFACE) }
        root.add(bar, h = Ui.dp(this, 60))
        addTab(bar, "Now Playing", R.drawable.ic_play) { NowPlayingScreen(this) }
        addTab(bar, "Playlist", R.drawable.ic_queue) { PlaylistScreen(this) }
        addTab(bar, "Library", R.drawable.ic_library) { LibraryScreen(this) }
        addTab(bar, "Sound", R.drawable.ic_equalizer) { SoundScreen(this) }
        addTab(bar, "Settings", R.drawable.ic_settings) { SettingsScreen(this) }
        setContentView(root)
        fitSystemBars(root)
        select(savedInstanceState?.getInt("tab") ?: Prefs.int("ui.tab", 0))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !Prefs.bool("ui.askedNotifications")
        ) {
            Prefs.set("ui.askedNotifications", true)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12)
        }
    }

    /** Android 15 draws edge to edge: keep the content clear of the bars. */
    private fun fitSystemBars(root: View) {
        if (Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom)
                if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else @Suppress("DEPRECATION") insets.consumeSystemWindowInsets()
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    private fun addTab(bar: LinearLayout, label: String, iconRes: Int, make: () -> Screen) {
        val index = tabs.size
        val tab = Ui.vertical(this).apply {
            gravity = Gravity.CENTER
            background = Ui.pressable(this@MainActivity, Color.TRANSPARENT, Ui.SURFACE2, 0)
            setOnClickListener { select(index) }
        }
        val icon = ImageView(this).apply { setImageResource(iconRes) }
        tab.add(icon, Ui.dp(this, 24), Ui.dp(this, 24))
        (tab.getChildAt(0).layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
        tab.add(Ui.text(this, label, 11f, Ui.DIM, lines = 1).apply { gravity = Gravity.CENTER })
        bar.add(tab, 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        tabs.add(tab to make)
    }

    fun select(index: Int) {
        val i = index.coerceIn(0, tabs.size - 1)
        if (i == current) return
        screens[current]?.hidden()
        current = i
        Prefs.set("ui.tab", i)
        val screen = screens.getOrPut(i) { tabs[i].second() }
        content.removeAllViews()
        (screen.view.parent as? ViewGroup)?.removeView(screen.view)
        content.addView(screen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        for ((k, t) in tabs.withIndex()) {
            val on = k == i
            (t.first.getChildAt(0) as ImageView).setColorFilter(if (on) Ui.ACCENT else Ui.DIM)
            (t.first.getChildAt(1) as TextView).setTextColor(if (on) Ui.ACCENT else Ui.DIM)
        }
        screen.shown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        screens[current]?.shown()
    }

    override fun onPause() {
        screens[current]?.hidden()
        super.onPause()
    }

    override fun onDestroy() {
        screens.values.forEach { it.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screens[current]?.back() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && PlaybackService.handleKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** Picks songs to copy into the app's music folder. */
    fun importSongs() {
        val action = if (Build.VERSION.SDK_INT >= 19) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_GET_CONTENT
        val intent = Intent(action).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
        if (Build.VERSION.SDK_INT >= 18) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(intent, REQUEST_IMPORT)
        } catch (e: Exception) {
            Ui.toast(this, "No app to pick files with")
        }
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(app.local.storagePermission), REQUEST_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSION) app.local.rescan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        if (Build.VERSION.SDK_INT >= 16) data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        app.local.importFiles(uris.distinct())
        Ui.toast(this, "Importing ${uris.distinct().size} song(s)…")
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Ui.toast(this, "No browser to open $url")
        }
    }
}
