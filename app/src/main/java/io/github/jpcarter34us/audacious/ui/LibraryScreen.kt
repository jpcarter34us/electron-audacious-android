package io.github.jpcarter34us.audacious.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.R
import io.github.jpcarter34us.audacious.library.LibraryItem
import io.github.jpcarter34us.audacious.library.LibraryLayout
import io.github.jpcarter34us.audacious.library.LibrarySource
import io.github.jpcarter34us.audacious.library.LibraryView
import io.github.jpcarter34us.audacious.library.MusicAssistantClient
import io.github.jpcarter34us.audacious.player.PlayOption
import io.github.jpcarter34us.audacious.ui.Ui.add
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.Images
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.text

/** The library browser (the iOS port's LibraryViews): a source, its
 *  sections, and views that open into further views. */
class LibraryScreen(activity: MainActivity) : Screen(activity) {
    private val app = App.app
    private var source: LibrarySource = app.sources.firstOrNull { it.id == Prefs.string("ui.source") } ?: app.sources.first()

    /** One level of the browser. */
    private class Page(val view: LibraryView?, val title: String) {
        val rows = ArrayList<Row>()
        val items = ArrayList<LibraryItem>()
        var offset = 0
        var done = false
        var loading = false
        var error: String? = null
        var scroll = 0
    }

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val item: LibraryItem) : Row()
        class Section(val title: String, val open: () -> LibraryView) : Row()
    }

    private val stack = ArrayList<Page>()
    private var lastConnected: Boolean? = null
    private val chips = Ui.horizontal(context)
    private val back = Ui.icon(context, R.drawable.ic_back, 40) { back() }
    private val title = Ui.text(context, "", 20f, bold = true, lines = 1)
    private val target = Ui.text(context, "", 13f, Ui.DIM, lines = 1)
    private val status = Ui.text(context, "", 14f, Ui.DIM).apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), 0) }
    private val playAll = Ui.button(context, "Play all") { playAllOf(PlayOption.PLAY) }
    private val search = Ui.field(context, "Search").apply {
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }
    private val maBar = Ui.horizontal(context).apply { setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0) }
    private val maPlayer = Ui.button(context, "Player") { pickPlayer() }
    private val maVolume = Ui.seek(context, 100, 0) { v, done -> if (done) app.musicAssistant.setVolume(v) }

    private val adapter = object : BaseAdapter() {
        val page get() = stack.lastOrNull()
        override fun getCount() = page?.rows?.size ?: 0
        override fun getItem(position: Int) = page!!.rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (page!!.rows[position] is Row.Header) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val r = page!!.rows[position]) {
            is Row.Header -> ((convertView as? TextView) ?: Ui.heading(context, "")).apply { text = r.title.uppercase() }
            is Row.Section -> row(convertView, r.title, null, null, null, true)
            is Row.Item -> row(convertView, r.item.name, r.item.subtitle?.takeIf { it.isNotEmpty() }
                ?: r.item.duration?.let { Ui.time(it) }, r.item.id, r.item.image, r.item.open != null)
        }
    }

    private val gridAdapter = object : BaseAdapter() {
        val page get() = stack.lastOrNull()
        override fun getCount() = page?.items?.size ?: 0
        override fun getItem(position: Int) = page!!.items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = page!!.items[position]
            val cell = (convertView as? LinearLayout) ?: Ui.vertical(context, 6).apply {
                val size = (parent.width / maxOf(1, (parent as GridView).numColumns)) - Ui.dp(context, 12)
                add(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Ui.rounded(Ui.SURFACE2, Ui.dp(context, 8).toFloat())
                }, h = maxOf(size, Ui.dp(context, 80)))
                add(Ui.text(context, "", 14f, lines = 1), top = 4)
                add(Ui.text(context, "", 12f, Ui.DIM, lines = 1))
            }
            Images.into(cell.getChildAt(0) as ImageView, item.id, item.image, placeholder(item.kind))
            (cell.getChildAt(1) as TextView).text = item.name
            (cell.getChildAt(2) as TextView).text = item.subtitle ?: ""
            return cell
        }
    }

    private fun placeholder(kind: String) = when (kind) {
        "artist" -> R.drawable.ic_person
        "playlist" -> R.drawable.ic_queue
        "folder" -> R.drawable.ic_folder
        "radio" -> R.drawable.ic_radio
        else -> R.drawable.ic_music
    }

    private fun row(convertView: View?, name: String, sub: String?, imageKey: String?, image: (() -> io.github.jpcarter34us.audacious.library.ImageRef?)?,
                    opens: Boolean): View {
        val r = (convertView as? LinearLayout) ?: Ui.horizontal(context).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
            add(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(Ui.SURFACE2, Ui.dp(context, 6).toFloat())
            }, Ui.dp(context, 44), Ui.dp(context, 44))
            add(Ui.vertical(context).apply {
                add(Ui.text(context, "", 16f, lines = 1))
                add(Ui.text(context, "", 13f, Ui.DIM, lines = 1))
            }, 0, weight = 1f, side = 12)
            add(Ui.text(context, "›", 22f, Ui.DIM), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val img = r.getChildAt(0) as ImageView
        img.visibility = if (imageKey != null) View.VISIBLE else View.GONE
        if (imageKey != null) Images.into(img, imageKey, image, R.drawable.ic_music)
        val texts = r.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).text = name
        (texts.getChildAt(1) as TextView).apply {
            text = sub ?: ""
            visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        r.getChildAt(2).visibility = if (opens) View.VISIBLE else View.INVISIBLE
        return r
    }

    private val scrollListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, state: Int) {}
        override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
            if (total > 0 && first + visible >= total - 10) loadMore()
        }
    }

    private val list = ListView(context).apply {
        adapter = this@LibraryScreen.adapter
        divider = null
        setOnScrollListener(scrollListener)
        setOnItemClickListener { _, _, i, _ -> tap(stack.last().rows.getOrNull(i)) }
        setOnItemLongClickListener { _, _, i, _ ->
            (stack.last().rows.getOrNull(i) as? Row.Item)?.let { options(it.item) }
            true
        }
    }

    private val grid = GridView(context).apply {
        adapter = gridAdapter
        numColumns = GridView.AUTO_FIT
        columnWidth = Ui.dp(context, 150)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        setOnScrollListener(scrollListener)
        setOnItemClickListener { _, _, i, _ -> stack.last().items.getOrNull(i)?.let { tap(Row.Item(it)) } }
        setOnItemLongClickListener { _, _, i, _ ->
            stack.last().items.getOrNull(i)?.let { options(it) }
            true
        }
    }

    override val view: View = Ui.vertical(context).apply {
        add(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        add(Ui.horizontal(context).apply {
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 12), 0)
            addView(back)
            add(Ui.vertical(context).apply {
                add(title)
                add(target)
            }, 0, weight = 1f, side = 6)
            add(playAll, ViewGroup.LayoutParams.WRAP_CONTENT)
        }, top = 4)
        add(maBar)
        add(search, top = 6, side = 12)
        add(status)
        val frame = android.widget.FrameLayout(context)
        frame.addView(list)
        frame.addView(grid)
        add(frame, h = 0, weight = 1f, top = 4)
    }

    init {
        maBar.add(maPlayer, 0, weight = 1f)
        maBar.addView(Ui.icon(context, R.drawable.ic_previous, 40) { app.musicAssistant.playerCommand("previous") })
        maBar.addView(Ui.icon(context, R.drawable.ic_play, 40) { app.musicAssistant.playerCommand("play_pause") })
        maBar.addView(Ui.icon(context, R.drawable.ic_next, 40) { app.musicAssistant.playerCommand("next") })
        maBar.add(maVolume, Ui.dp(context, 110))
        search.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                runSearch()
                true
            } else false
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty() && stack.lastOrNull()?.title?.startsWith("Search") == true) back()
            }
        })
        for (s in app.sources) watch(s.changed.watch { if (s === source) sourceChanged() })
        buildChips()
        openRoot()
    }

    private fun buildChips() {
        chips.removeAllViews()
        chips.setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), 0)
        for (s in app.sources) {
            val on = s === source
            chips.add(Ui.button(context, s.label, primary = on) {
                source = s
                Prefs.set("ui.source", s.id)
                buildChips()
                openRoot()
            }, ViewGroup.LayoutParams.WRAP_CONTENT, side = 4)
        }
    }

    private fun sourceChanged() {
        val connected = source.isConnected
        if (connected != lastConnected || (stack.size == 1 && stack[0].view == null)) openRoot() else refreshHeader()
    }

    private fun openRoot() {
        lastConnected = source.isConnected
        stack.clear()
        val root = Page(null, source.label)
        if (source.isConnected) for ((name, make) in source.sections) root.rows.add(Row.Section(name, make))
        root.done = true
        stack.add(root)
        show()
    }

    private fun push(view: LibraryView) {
        stack.lastOrNull()?.scroll = (if (stack.last().view?.layout == LibraryLayout.GRID) grid else list).firstVisiblePosition
        stack.add(Page(view, view.title))
        show()
        loadMore()
    }

    override fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        show()
        val page = stack.last()
        (if (page.view?.layout == LibraryLayout.GRID) grid else list).setSelection(page.scroll)
        return true
    }

    private fun show() {
        val page = stack.last()
        val gridMode = page.view?.layout == LibraryLayout.GRID && page.view.sections == null
        grid.visibility = if (gridMode) View.VISIBLE else View.GONE
        list.visibility = if (gridMode) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        gridAdapter.notifyDataSetChanged()
        refreshHeader()
    }

    private fun refreshHeader() {
        val page = stack.last()
        title.text = page.title
        back.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        playAll.visibility = if (page.view?.playAll != null && page.items.isNotEmpty()) View.VISIBLE else View.GONE
        target.text = source.targetText
        search.visibility = if (source.isConnected) View.VISIBLE else View.GONE
        status.text = when {
            !source.isConnected -> source.statusText
            page.error != null -> page.error
            page.loading && page.rows.isEmpty() -> "Loading…"
            page.done && page.rows.isEmpty() && page.view != null -> "Nothing here."
            source.id == "local" && page.view == null && !app.local.hasPermission ->
                "Showing the app's music folder. Allow access to music in Settings › On This Device to see the rest of this device's songs."
            else -> ""
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val ma = source as? MusicAssistantClient
        maBar.visibility = if (ma != null && ma.isConnected) View.VISIBLE else View.GONE
        if (ma != null) {
            maPlayer.text = ma.selectedPlayer?.name ?: "Choose a player"
            ma.selectedPlayer?.volume?.let { maVolume.progress = it }
        }
    }

    private fun loadMore() {
        val page = stack.last()
        val v = page.view ?: return
        if (page.loading || page.done) return
        page.loading = true
        refreshHeader()
        val sections = v.sections
        val offset = page.offset
        Bg.load({
            if (sections != null) sections().flatMap { s -> if (s.items.isEmpty()) emptyList() else listOf<Any>(s.title) + s.items }
            else v.load(offset)
        }) { result ->
            page.loading = false
            result.onSuccess { got ->
                if (sections != null || got.isEmpty()) page.done = true
                for (x in got) when (x) {
                    is String -> page.rows.add(Row.Header(x))
                    is LibraryItem -> {
                        page.rows.add(Row.Item(x))
                        page.items.add(x)
                    }
                }
                page.offset += got.size
            }.onFailure {
                page.error = it.text
                page.done = true
            }
            if (stack.lastOrNull() === page) show()
        }
    }

    private fun tap(row: Row?) {
        when (row) {
            is Row.Section -> push(row.open())
            is Row.Item -> {
                val open = row.item.open
                if (open != null) push(open()) else play(row.item, PlayOption.PLAY)
            }
            else -> {}
        }
    }

    private fun options(item: LibraryItem) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (item.play != null) {
            actions.add("Play" to { play(item, PlayOption.PLAY) })
            actions.add("Play next" to { play(item, PlayOption.NEXT) })
            actions.add("Add to queue" to { play(item, PlayOption.ADD) })
        }
        item.open?.let { o -> actions.add("Open" to { push(o()) }) }
        if (actions.isEmpty()) return
        Ui.choose(context, item.name, actions.map { it.first }) { actions[it].second() }
    }

    private fun play(item: LibraryItem, option: PlayOption) {
        val p = item.play ?: return
        Bg.load({ p(option) }) { r ->
            r.onSuccess { Ui.toast(context, "${option.verb}${if (it == "this device") "" else " on $it"}: ${item.name}") }
                .onFailure { Ui.toast(context, it.text) }
        }
    }

    private fun playAllOf(option: PlayOption) {
        val page = stack.last()
        val f = page.view?.playAll ?: return
        val items = ArrayList(page.items)
        Bg.load({ f(option, items) }) { r ->
            r.onSuccess { Ui.toast(context, "${option.verb}: ${page.title}") }.onFailure { Ui.toast(context, it.text) }
        }
    }

    private fun runSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty() || !source.isConnected) return
        val within = stack.getOrNull(1)?.title
        while (stack.size > 1 && stack.last().title.startsWith("Search")) stack.removeAt(stack.size - 1)
        push(source.search(q, within))
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun pickPlayer() {
        val ma = app.musicAssistant
        val players = ma.players
        if (players.isEmpty()) return Ui.toast(context, "No Music Assistant players")
        Ui.choose(context, "Play on", players.map { p -> p.name + (p.state?.let { " · $it" } ?: "") }) {
            ma.selectedPlayerID = players[it].id
        }
    }
}
