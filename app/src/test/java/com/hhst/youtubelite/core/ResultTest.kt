package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Result] and its extension functions. Verifies the
 * Success/Failure semantics, mapping, and propagation.
 */
class ResultTest {

    @Test
    fun `success holds value`() {
        val result: Result<String> = Result.Success("hello")
        assertTrue(result.isSuccess())
        assertFalse(result.isFailure())
        assertEquals("hello", result.getOrNull())
        assertEquals("hello", result.getOrThrow())
    }

    @Test(expected = IllegalStateException::class)
    fun `getOrThrow on failure rethrows error`() {
        val result: Result<String> = Result.Failure(IllegalStateException("boom"))
        result.getOrThrow()
    }

    @Test
    fun `failure holds error`() {
        val error = RuntimeException("kaboom")
        val result: Result<String> = Result.Failure(error)
        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
        assertNull(result.getOrNull())
        assertEquals(error, (result as Result.Failure).error)
    }

    @Test
    fun `map transforms success value`() {
        val result: Result<Int> = Result.Success(21)
        val mapped = result.map { it * 2 }
        assertEquals(42, mapped.getOrNull())
    }

    @Test
    fun `map preserves failure`() {
        val error = RuntimeException("nope")
        val result: Result<Int> = Result.Failure(error)
        val mapped = result.map { it * 2 }
        assertTrue(mapped.isFailure())
        assertEquals(error, (mapped as Result.Failure).error)
    }

    @Test
    fun `flatMap chains successful computations`() {
        val result: Result<Int> = Result.Success(2)
        val chained = result.flatMap { x -> Result.Success(x + 40) }
        assertEquals(42, chained.getOrNull())
    }

    @Test
    fun `flatMap propagates failure without calling block`() {
        val error = RuntimeException("nope")
        var blockCalled = false
        val result: Result<Int> = Result.Failure(error)
        val chained = result.flatMap {
            blockCalled = true
            Result.Success(it)
        }
        assertTrue(chained.isFailure())
        assertFalse(blockCalled)
    }

    @Test
    fun `onSuccess invokes action only for success`() {
        var captured: String? = null
        Result.Success("ok").onSuccess { captured = it }
        assertEquals("ok", captured)

        captured = null
        Result.Failure(RuntimeException()).onSuccess { captured = "should not happen" }
        assertNull(captured)
    }

    @Test
    fun `onFailure invokes action only for failure`() {
        var captured: Throwable? = null
        val error = RuntimeException("err")
        Result.Failure(error).onFailure { captured = it }
        assertEquals(error, captured)

        captured = null
        Result.Success(42).onFailure { captured = RuntimeException("should not happen") }
        assertNull(captured)
    }

    @Test
    fun `of wraps thrown exception as Failure`() {
        val result: Result<String> = Result.of { throw IllegalStateException("boom") }
        assertTrue(result.isFailure())
        assertTrue((result as Result.Failure).error is IllegalStateException)
    }

    @Test
    fun `of wraps successful value as Success`() {
        val result: Result<String> = Result.of { "ok" }
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `resultOf inline factory wraps thrown exception`() {
        val result: Result<Int> = resultOf { error("fail") }
        assertTrue(result.isFailure())
    }

    @Test
    fun `companion factories construct correct subtypes`() {
        val success: Result<String> = Result.success("ok")
        assertTrue(success is Result.Success)
        assertEquals("ok", success.getOrNull())

        val failure: Result<String> = Result.failure(RuntimeException("err"))
        assertTrue(failure is Result.Failure)
    }
}
