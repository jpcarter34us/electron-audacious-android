package io.github.jpcarter34us.audacious.ui

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import io.github.jpcarter34us.audacious.App
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.ui.Ui.add

/** The playlist (the iOS port's playlist list): tap to play, long-press
 *  for moving and removing. */
class PlaylistScreen(activity: MainActivity) : Screen(activity) {
    private val player = App.app.player
    private val count = Ui.text(context, "", 13f, Ui.DIM)
    private val empty = Ui.text(context, "The playlist is empty. Add songs from the Library.", 15f, Ui.DIM).apply {
        setPadding(Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16), 0)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = player.tracks.size
        override fun getItem(position: Int) = player.tracks[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? android.widget.LinearLayout) ?: Ui.vertical(context).apply {
                setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
                add(Ui.text(context, "", 16f, lines = 1))
                add(Ui.text(context, "", 13f, Ui.DIM, lines = 1))
            }
            val t = player.tracks[position]
            val current = t.id == player.currentID
            (row.getChildAt(0) as TextView).apply {
                text = (if (current) (if (player.state == Player.State.PLAYING) "▶ " else "❚❚ ") else "") + t.title
                setTextColor(if (current) Ui.ACCENT else Ui.TEXT)
            }
            (row.getChildAt(1) as TextView).text = listOf(t.subtitle, Ui.time(t.duration)).filter { it.isNotEmpty() && it != "--:--" }.joinToString(" · ")
            return row
        }
    }

    private val list = ListView(context).apply {
        adapter = this@PlaylistScreen.adapter
        divider = null
        setOnItemClickListener { _, _, i, _ -> player.play(player.tracks[i].id) }
        setOnItemLongClickListener { _, _, i, _ ->
            menu(i)
            true
        }
    }

    override val view: View = Ui.vertical(context).apply {
        add(Ui.horizontal(context).apply {
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
            add(Ui.vertical(context).apply {
                add(Ui.text(context, "Playlist", 22f, bold = true))
                add(count)
            }, 0, weight = 1f)
            add(Ui.button(context, "Clear") {
                if (player.tracks.isNotEmpty()) Ui.dialog(context).setTitle("Clear the playlist?")
                    .setPositiveButton("Clear") { _, _ -> player.clear() }.setNegativeButton("Cancel", null).show()
            }, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        add(empty)
        add(list, h = 0, weight = 1f)
    }

    init {
        watch(player.changed.watch { refresh() })
        refresh()
    }

    override fun shown() {
        refresh()
        player.currentIndex?.let { list.setSelection(maxOf(0, it - 2)) }
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val total = player.tracks.sumOf { it.duration ?: 0.0 }
        count.text = "${player.tracks.size} songs · ${Ui.time(total)}"
        empty.visibility = if (player.tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun menu(i: Int) {
        val t = player.tracks.getOrNull(i) ?: return
        val options = listOf("Play", "Move up", "Move down", "Move to top", "Remove", "Remove all others")
        Ui.choose(context, t.title, options) { o ->
            when (o) {
                0 -> player.play(t.id)
                1 -> player.move(i, i - 1)
                2 -> player.move(i, i + 1)
                3 -> player.move(i, 0)
                4 -> player.remove(listOf(i))
                5 -> player.remove(player.tracks.indices.filter { it != i })
            }
        }
    }
}
