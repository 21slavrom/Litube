package com.hhst.youtubelite.core

import java.util.function.Predicate
import kotlin.math.min
import kotlin.math.pow

/**
 * Configuration for [Retry.with]. Controls how many attempts are made, how long
 * to wait between attempts (exponential backoff capped by [maxDelayMs]), and
 * which exceptions trigger a retry via [retryOn].
 *
 * Predefined policies live on the companion and are tuned for the specific
 * failure modes of the call sites they serve. Do not introduce ad-hoc policies
 * inline — extend the companion so the catalog stays observable.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val multiplier: Double = 2.0,
    val retryOn: Predicate<Throwable> = Predicate { true }
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be > 0, got $maxAttempts" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0, got $baseDelayMs" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0, got $multiplier" }
    }

    /**
     * Backoff duration (ms) for the given 1-based attempt index. After attempt
     * `n` fails, the next attempt is delayed by `delayFor(n)`.
     */
    fun delayFor(attempt: Int): Long {
        val raw = (baseDelayMs * multiplier.pow(attempt - 1)).toLong()
        return min(raw, maxDelayMs)
    }

    companion object {

        /**
         * Policy for opening upstream HTTP connections (YouTube stream URLs,
         * sidx byte ranges). Transient TLS resets and read timeouts from
         * YouTube's CDN are common on flaky networks; retrying on IO errors
         * with a fresh connection masks these. Matches the historical
         * LocalStreamProxy#openWithRetry cadence (3 attempts, 500ms base,
         * 2x backoff).
         */
        @JvmField
        val UPSTREAM_OPEN = RetryPolicy(
            maxAttempts = 3,
            baseDelayMs = 500L,
            maxDelayMs = 5_000L,
            multiplier = 2.0,
            retryOn = Predicate { it is java.io.IOException }
        )

        /**
         * Policy for sidx pre-fetch. Two attempts: the extractor-provided
         * range first, then a widened range that lets the SidxParser scanner
         * walk through styp/emsg preamble boxes.
         */
        @JvmField
        val SIDX_FETCH = RetryPolicy(
            maxAttempts = 2,
            baseDelayMs = 1_000L,
            maxDelayMs = 3_000L,
            multiplier = 1.0
        )

        @JvmStatic
        fun upstreamOpen(): RetryPolicy = UPSTREAM_OPEN

        @JvmStatic
        fun sidxFetch(): RetryPolicy = SIDX_FETCH
    }
}
