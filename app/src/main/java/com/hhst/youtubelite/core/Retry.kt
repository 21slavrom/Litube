package com.hhst.youtubelite.core

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Retries [block] according to [policy]. The block receives the 1-based attempt
 * number so call sites can refresh state (e.g. rotate a fresh URL on the second
 * attempt).
 *
 * If the block throws and [RetryPolicy.retryOn] returns true and the attempt
 * budget is not exhausted, the coroutine sleeps for [RetryPolicy.delayFor]
 * milliseconds (non-blocking) and tries again. The last exception is rethrown
 * if all attempts fail or [RetryPolicy.retryOn] returns false.
 *
 * Example:
 * ```
 * val source = Retry.with(RetryPolicy.UPSTREAM_OPEN) { attempt ->
 *     dataSourceFactory.createDataSource().also { it.open(dataSpec) }
 * }
 * ```
 *
 * This is a suspend function. Java callers should reach it from Kotlin adapter
 * code that owns the coroutine scope (e.g. the cast proxy's pre-fetch entry
 * point) rather than blocking the calling thread.
 */
object Retry {

    private const val TAG = "Retry"

    suspend fun <T> with(policy: RetryPolicy, block: suspend (attempt: Int) -> T): T {
        var lastError: Throwable? = null
        for (attempt in 1..policy.maxAttempts) {
            try {
                return block(attempt)
            } catch (e: Throwable) {
                lastError = e
                val shouldRetry = policy.retryOn.test(e) && attempt < policy.maxAttempts
                if (!shouldRetry) {
                    throw e
                }
                val sleepMs = policy.delayFor(attempt)
                Log.d(
                    TAG,
                    "attempt $attempt failed (${e.javaClass.simpleName}), " +
                            "retrying in ${sleepMs}ms: ${e.message}"
                )
                delay(sleepMs)
            }
        }
        // Unreachable: the loop either returns on success or throws on the final attempt.
        throw lastError ?: IllegalStateException("Retry exhausted without exception")
    }

    /**
     * Synchronous adapter for [with] that bridges coroutine-based retry to blocking
     * call sites (e.g. NanoHTTPD's worker-thread `serve()`). The calling thread is
     * blocked until [block] completes or all attempts are exhausted; the inter-attempt
     * delay still uses non-blocking `delay()`, so this does not pin a dispatcher thread.
     *
     * Prefer [with] from suspend code; use this only when the call site cannot be
     * suspended (legacy synchronous APIs, Java callers without a coroutine scope).
     */
    @JvmStatic
    fun <T> withBlocking(policy: RetryPolicy, block: BlockingBlock<T>): T =
        runBlocking {
            with(policy) { attempt -> block.invoke(attempt) }
        }

    /**
     * Functional interface that allows Java callers to pass a lambda that throws
     * checked exceptions. Kotlin lambdas can throw freely, but Java lambdas need
     * the receiving interface to declare `throws Throwable` so the compiler accepts
     * a body that may throw.
     */
    fun interface BlockingBlock<T> {
        @Throws(Throwable::class)
        operator fun invoke(attempt: Int): T
    }
}

