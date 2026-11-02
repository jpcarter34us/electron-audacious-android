package io.github.jpcarter34us.audacious.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import io.github.jpcarter34us.audacious.library.ImageRef

/** Covers, loaded in the background and kept in a small memory cache
 *  (the iOS port's RemoteImage). */
object Images {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.rowBytes * value.height
    }
    private val missing = HashSet<String>()

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Blocks. */
    fun load(context: Context, ref: ImageRef, size: Int = 300): Bitmap? {
        cache.get(ref.key)?.let { return it }
        synchronized(missing) { if (ref.key in missing) return null }
        val bytes: ByteArray? = try {
            when (ref) {
                is ImageRef.Remote -> Http.client.newCall(Http.request(ref.url, ref.headers)).execute().use {
                    if (it.isSuccessful) it.body()?.bytes() else null
                }
                is ImageRef.Embedded -> MediaMetadataRetriever().let { r ->
                    try {
                        if (ref.path.startsWith("content://")) r.setDataSource(context, Uri.parse(ref.path)) else r.setDataSource(ref.path)
                        r.embeddedPicture
                    } finally {
                        r.release()
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
        val bitmap = bytes?.let { decode(it, size) }
        if (bitmap != null) cache.put(ref.key, bitmap) else synchronized(missing) { missing.add(ref.key) }
        return bitmap
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Shows the cover from `resolve` in `view` (which may be recycled meanwhile). */
    fun into(view: ImageView, key: String, resolve: (() -> ImageRef?)?, placeholder: Int) {
        view.tag = key
        val ready = cache.get(key)
        if (ready != null) {
            showBitmap(view, ready)
            return
        }
        // The placeholder is a small icon: centred, not stretched.
        view.scaleType = ImageView.ScaleType.CENTER
        view.setImageResource(placeholder)
        if (resolve == null) return
        Bg.load({ resolve()?.let { ref -> load(view.context, ref)?.also { cache.put(key, it) } } }) { result ->
            val bmp = result.getOrNull()
            if (bmp != null && view.tag == key) showBitmap(view, bmp)
        }
    }

    private fun showBitmap(view: ImageView, bmp: Bitmap) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    }
}
