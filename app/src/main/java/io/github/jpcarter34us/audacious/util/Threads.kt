package io.github.jpcarter34us.audacious.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** The main (UI) thread, where the models live, as the iOS port's @MainActor. */
object Main {
    val handler = Handler(Looper.getMainLooper())

    val isMain get() = Looper.myLooper() == Looper.getMainLooper()

    fun post(r: () -> Unit) {
        if (isMain) r() else handler.post(r)
    }

    /** Always later, even from the main thread. */
    fun later(r: () -> Unit) {
        handler.post(r)
    }

    fun delay(ms: Long, r: Runnable) {
        handler.postDelayed(r, ms)
    }

    fun cancel(r: Runnable) {
        handler.removeCallbacks(r)
    }
}

/** Background work (network, disk): a small shared pool. */
object Bg {
    private val count = AtomicInteger()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "bg-${count.incrementAndGet()}").apply { isDaemon = true }
    })

    fun run(work: () -> Unit) {
        pool.execute(work)
    }

    /** Runs `work` in the background, then `done` on the main thread. */
    fun <T> load(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            Main.post { done(result) }
        }
    }
}

/** "Something changed" for the screens watching a model; listeners run on
 *  the main thread. */
class Signal {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Returns the function that removes the listener. */
    fun watch(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun emit() {
        Main.post { for (l in listeners) l() }
    }
}

/** An error message for the user. */
class UserError(message: String) : Exception(message)

val Throwable.text: String get() = message ?: javaClass.simpleName
