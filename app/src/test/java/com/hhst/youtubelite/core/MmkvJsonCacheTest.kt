package com.hhst.youtubelite.core

import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

/**
 * Unit tests for [MmkvJsonCache]. Mocks [MMKV] since the real implementation
 * requires native libraries and a Context. Verifies the Slot wrapper, TTL
 * expiry, invalidation, and graceful handling of decode failures (the
 * fallback trap that previously was silently swallowed).
 */
class MmkvJsonCacheTest {

    private data class TestData(val name: String, val count: Long)

    private lateinit var mmkv: MMKV
    private lateinit var cache: MmkvJsonCache
    private val gson = Gson()

    @Before
    fun setUp() {
        mmkv = mock(MMKV::class.java)
        cache = MmkvJsonCache(mmkv, gson)
    }

    @Test
    fun `get returns null when key absent`() {
        whenever(mmkv.decodeString(eq("missing"), any())).thenReturn(null)
        assertNull(cache.get("missing", TestData::class.java))
    }

    @Test
    fun `get returns null when stored value is empty string`() {
        whenever(mmkv.decodeString(eq("empty"), any())).thenReturn("")
        assertNull(cache.get("empty", TestData::class.java))
    }

    @Test
    fun `put then get roundtrips value within TTL`() {
        val value = TestData("hello", 42)

        cache.put("key", value, ttlMillis = 3_600_000)

        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(mmkv).encode(eq("key"), captor.capture())

        // Simulate a subsequent read by returning the encoded Slot.
        whenever(mmkv.decodeString(eq("key"), any())).thenReturn(captor.value)

        val retrieved = cache.get("key", TestData::class.java)
        assertEquals(value, retrieved)
    }

    @Test
    fun `get returns null for expired entry`() {
        val value = TestData("hello", 42)

        // TTL of -1 means the entry is already expired at write time.
        cache.put("key", value, ttlMillis = -1)

        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(mmkv).encode(eq("key"), captor.capture())
        whenever(mmkv.decodeString(eq("key"), any())).thenReturn(captor.value)

        assertNull(cache.get("key", TestData::class.java))
    }

    @Test
    fun `invalidate removes key`() {
        cache.invalidate("key")
        verify(mmkv).removeValueForKey("key")
    }

    @Test
    fun `get returns null on corrupted JSON without throwing`() {
        // Slot wrapper is itself valid JSON but the inner payload is garbage.
        // Verify the cache surfaces a null and does not propagate the exception.
        val corruptedSlot = """{"until":9999999999999,"json":"!!not-json!!"}"""
        whenever(mmkv.decodeString(eq("bad"), any())).thenReturn(corruptedSlot)

        assertNull(cache.get("bad", TestData::class.java))
    }

    @Test
    fun `get returns null when stored slot is not valid JSON`() {
        whenever(mmkv.decodeString(eq("bad-slot"), any())).thenReturn("not even a slot")
        assertNull(cache.get("bad-slot", TestData::class.java))
    }
}
