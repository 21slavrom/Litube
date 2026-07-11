package com.hhst.youtubelite.extractor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-backed async fill of a mutable [value].
 *
 * [work] runs on [scope] (IO). Call [get] only for branches you need.
 */
class Promise<T>(
    val value: T,
    scope: CoroutineScope = DEFAULT_SCOPE,
    work: (Promise<T>) -> Unit,
) {
    private val startedAtNs = System.nanoTime()
    private val deferred = CompletableDeferred<T>()
    private val failure = AtomicReference<Throwable?>(null)
    private val doneListeners = CopyOnWriteArrayList<() -> Unit>()
    private val doneLock = Any()

    @Volatile
    var done: Boolean = false
        private set

    /** Milliseconds from construction to settlement; `-1` until done. */
    @Volatile
    var elapsedMs: Long = -1L
        private set

    val error: Throwable?
        get() = failure.get()

    val success: Boolean
        get() = done && failure.get() == null

    init {
        scope.launch(Dispatchers.IO) {
            try {
                work(this@Promise)
                deferred.complete(value)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                deferred.completeExceptionally(t)
            } finally {
                elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                synchronized(doneLock) {
                    done = true
                    doneListeners.forEach { listener -> runCatching { listener() } }
                    doneListeners.clear()
                }
            }
        }
    }

    /** Blocks until complete; rethrows if [work] failed. */
    fun get(): T = runBlocking {
        deferred.await()
    }

    /** Like [get], with timeout. */
    fun get(timeout: Long, unit: TimeUnit): T = runBlocking {
        withTimeout(unit.toMillis(timeout).milliseconds) {
            deferred.await()
        }
    }

    /** Mutates [value] in place. */
    inline fun set(block: T.() -> Unit) {
        value.block()
    }

    /** Runs [listener] once after settlement (success or failure). */
    fun whenDone(listener: () -> Unit) {
        synchronized(doneLock) {
            if (done) {
                listener()
                return
            }
            doneListeners.add(listener)
        }
    }

    companion object {
        internal val DEFAULT_SCOPE: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
