package com.hhst.youtubelite.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.function.Predicate

/**
 * Unit tests for [Retry]. Uses [runTest] so virtual time advances past backoff
 * delays instantly — the tests assert attempt counts and final outcomes, not
 * wall-clock timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {

    @Test
    fun `succeeds on first attempt without delay`() = runTest {
        val result = Retry.with(RetryPolicy(maxAttempts = 3)) { 42 }
        assertEquals(42, result)
        // No backoff delay should have elapsed.
        assertEquals(0, currentTime)
    }

    @Test
    fun `retries on retryable failure then succeeds`() = runTest {
        var attempts = 0
        val result = Retry.with(
            RetryPolicy(maxAttempts = 3, baseDelayMs = 100, multiplier = 1.0)
        ) { attempt ->
            attempts++
            if (attempt < 3) throw IOException("transient")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
        // Two backoffs of 100ms each (multiplier = 1.0).
        assertEquals(200, currentTime)
    }

    @Test
    fun `throws last error after exhausting attempts`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            Retry.with(
                RetryPolicy(maxAttempts = 2, baseDelayMs = 10, multiplier = 1.0)
            ) {
                attempts++
                throw IOException("always")
            }
        }
        assertTrue(thrown.isFailure)
        val err = thrown.exceptionOrNull()
        assertTrue("expected IOException, got $err", err is IOException)
        assertEquals("always", err?.message)
        assertEquals(2, attempts)
    }

    @Test
    fun `does not retry when retryOn predicate returns false`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            Retry.with(
                RetryPolicy(
                    maxAttempts = 3,
                    retryOn = Predicate { it is IOException }
                )
            ) {
                attempts++
                throw IllegalArgumentException("not retryable")
            }
        }
        assertTrue(thrown.isFailure)
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, attempts)
        // No backoff because predicate returned false on the first failure.
        assertEquals(0, currentTime)
    }

    @Test
    fun `predicate filters which errors are retried`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            Retry.with(
                RetryPolicy(
                    maxAttempts = 3,
                    baseDelayMs = 10,
                    multiplier = 1.0,
                    retryOn = Predicate { it is IOException }
                )
            ) {
                attempts++
                // First throw is retryable, second is not.
                throw if (attempts == 1) IOException("io") else IllegalStateException("fatal")
            }
        }
        assertTrue(thrown.isFailure)
        val err = thrown.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $err", err is IllegalStateException)
        assertEquals(2, attempts)
        // One backoff between attempt 1 and 2.
        assertEquals(10, currentTime)
    }

    @Test
    fun `delayFor applies exponential backoff up to max`() {
        val policy = RetryPolicy(
            maxAttempts = 5,
            baseDelayMs = 100,
            maxDelayMs = 1_000,
            multiplier = 2.0
        )
        assertEquals(100, policy.delayFor(1))
        assertEquals(200, policy.delayFor(2))
        assertEquals(400, policy.delayFor(3))
        assertEquals(800, policy.delayFor(4))
        // Capped at maxDelayMs.
        assertEquals(1_000, policy.delayFor(5))
    }

    @Test
    fun `predefined UPSTREAM_OPEN policy retries on IOException only`() {
        val policy = RetryPolicy.UPSTREAM_OPEN
        assertTrue(policy.retryOn.test(IOException("io")))
        // Non-IO exceptions should not be retried by this policy.
        assertFalse(policy.retryOn.test(RuntimeException("runtime")))
    }
}
