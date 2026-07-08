package com.hhst.youtubelite.extractor

import android.util.Log
import com.hhst.youtubelite.core.AppScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * Supplier that may throw a checked exception. Used by [SegmentPoll] so Java
 * callers can pass method references (e.g. `youtube::getStreamSegments`) to
 * methods that declare `throws ParsingException` without wrapping them in a
 * try/catch lambda.
 */
fun interface PolledSupplier<T> {
    @Throws(Exception::class)
    fun get(): T?
}

/**
 * Polls a [PolledSupplier] until it returns a non-empty list, then invokes
 * [callback] with the result. Replaces the `Thread.sleep` polling pattern
 * previously used by `YoutubeExtractor.scheduleSegmentRefresh` with structured
 * concurrency: the delay between polls is a non-blocking [delay] that frees the
 * dispatcher thread for other work instead of blocking it like `Thread.sleep`.
 *
 * Behavior contract:
 * - On success: [callback] is invoked once with the first non-empty list.
 * - On timeout: [callback] is never invoked; a debug log is emitted.
 * - On supplier exception: polling aborts; a warning log is emitted; [callback]
 *   is never invoked (matches the previous `catch (Exception) { return; }`).
 *
 * @param T          element type of the polled list
 * @param supplier    returns the list to check; may throw or return null
 * @param intervalMs  delay between polls, in milliseconds
 * @param timeoutMs   maximum total wait before giving up, in milliseconds
 * @param callback    invoked on the [AppScope] dispatcher with the first
 *                    non-empty result
 */
object SegmentPoll {

    private const val TAG = "SegmentPoll"

    @JvmStatic
    fun <T> pollUntilNonEmpty(
        supplier: PolledSupplier<List<T>>,
        intervalMs: Long,
        timeoutMs: Long,
        callback: Consumer<List<T>>
    ) {
        AppScope.scope.launch {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val result = supplier.get()
                    if (!result.isNullOrEmpty()) {
                        callback.accept(result)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pollUntilNonEmpty: supplier threw, aborting poll", e)
                    return@launch
                }
                delay(intervalMs)
            }
            Log.d(TAG, "pollUntilNonEmpty: timed out after ${timeoutMs}ms without a result")
        }
    }
}
