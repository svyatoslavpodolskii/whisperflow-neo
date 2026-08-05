package com.pimax.whisperflow

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SettingsDataStore constants — verifies no private IPs are baked in
 * and all defaults are in a sensible range.
 */
class SettingsDefaultsTest {

    @Test
    fun `DEFAULT_SERVER_URL is empty — user must configure`() {
        assertEquals("", SettingsDataStore.DEFAULT_SERVER_URL)
    }

    @Test
    fun `DEFAULT_TASKS_URL is empty — user must configure`() {
        assertEquals("", SettingsDataStore.DEFAULT_TASKS_URL)
    }

    @Test
    fun `DEFAULT_STREAK_URL is empty — user must configure`() {
        assertEquals("", SettingsDataStore.DEFAULT_STREAK_URL)
    }

    @Test
    fun `DEFAULT_ASSISTANT_URL is empty — user must configure`() {
        assertEquals("", SettingsDataStore.DEFAULT_ASSISTANT_URL)
    }

    @Test
    fun `DEFAULT_GATEWAY_URL is empty — user must configure`() {
        assertEquals("", SettingsDataStore.DEFAULT_GATEWAY_URL)
    }

    @Test
    fun `DEFAULT_LANGUAGE is Auto`() {
        assertEquals("Auto", SettingsDataStore.DEFAULT_LANGUAGE)
    }

    @Test
    fun `DEFAULT_TASK is transcribe`() {
        assertEquals("transcribe", SettingsDataStore.DEFAULT_TASK)
    }

    @Test
    fun `DEFAULT_INFERENCE_MODE is GPU_SERVER`() {
        assertEquals("GPU_SERVER", SettingsDataStore.DEFAULT_INFERENCE_MODE)
    }

    @Test
    fun `DEFAULT_LOCAL_MODEL is TINY`() {
        assertEquals("TINY", SettingsDataStore.DEFAULT_LOCAL_MODEL)
    }

    @Test
    fun `DEFAULT_CHUNK_INTERVAL_MS is positive and reasonable`() {
        val v = SettingsDataStore.DEFAULT_CHUNK_INTERVAL_MS
        assertTrue("chunk interval should be >= 500ms", v >= 500L)
        assertTrue("chunk interval should be <= 5000ms", v <= 5000L)
    }

    @Test
    fun `DEFAULT_SILENCE_TIMEOUT_MS is positive and reasonable`() {
        val v = SettingsDataStore.DEFAULT_SILENCE_TIMEOUT_MS
        assertTrue("silence timeout should be >= 500ms", v >= 500L)
        assertTrue("silence timeout should be <= 10000ms", v <= 10000L)
    }

    @Test
    fun `DEFAULT_HALLUCINATION_THRESHOLD is between 0 and 1`() {
        val v = SettingsDataStore.DEFAULT_HALLUCINATION_THRESHOLD
        assertTrue("hallucination threshold should be > 0", v > 0f)
        assertTrue("hallucination threshold should be < 1", v < 1f)
    }

    @Test
    fun `DEFAULT_SERVER_PORT is a valid port number`() {
        val p = SettingsDataStore.DEFAULT_SERVER_PORT
        assertTrue("server port should be > 1024", p > 1024)
        assertTrue("server port should be < 65536", p < 65536)
    }

    @Test
    fun `no default URL contains a private IP`() {
        val defaults = listOf(
            SettingsDataStore.DEFAULT_SERVER_URL,
            SettingsDataStore.DEFAULT_TASKS_URL,
            SettingsDataStore.DEFAULT_STREAK_URL,
            SettingsDataStore.DEFAULT_ASSISTANT_URL,
            SettingsDataStore.DEFAULT_GATEWAY_URL
        )
        val privateIpPattern = Regex("""(192\.168\.|10\.\d+\.|100\.\d+\.)""")
        defaults.forEach { url ->
            assertFalse(
                "Found private IP in default URL: $url",
                privateIpPattern.containsMatchIn(url)
            )
        }
    }
}
