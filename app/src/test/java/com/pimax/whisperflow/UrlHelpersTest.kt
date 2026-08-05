package com.pimax.whisperflow

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the deriveServiceUrl() helper — core URL-building logic used by all
 * connection fields in the app.
 */
class UrlHelpersTest {

    // ── deriveServiceUrl ──────────────────────────────────────────────────────

    @Test
    fun `deriveServiceUrl extracts host and substitutes port`() {
        val result = deriveServiceUrl("http://192.168.1.10:7862", 8058)
        assertEquals("http://192.168.1.10:8058", result)
    }

    @Test
    fun `deriveServiceUrl works with Tailscale-style IP`() {
        val result = deriveServiceUrl("http://100.70.125.67:7862", 8055)
        assertEquals("http://100.70.125.67:8055", result)
    }

    @Test
    fun `deriveServiceUrl works with hostname`() {
        val result = deriveServiceUrl("http://myserver.local:7862", 8068)
        assertEquals("http://myserver.local:8068", result)
    }

    @Test
    fun `deriveServiceUrl returns empty string when base is empty`() {
        val result = deriveServiceUrl("", 8058)
        assertEquals("", result)
    }

    @Test
    fun `deriveServiceUrl returns empty string for unparseable URL`() {
        val result = deriveServiceUrl("not-a-url", 8058)
        assertEquals("", result)
    }

    @Test
    fun `deriveServiceUrl preserves port zero for explicit zero`() {
        val result = deriveServiceUrl("http://myhost:7862", 0)
        assertEquals("http://myhost:0", result)
    }

    // ── authUrlFromTasksUrl ───────────────────────────────────────────────────

    @Test
    fun `authUrlFromTasksUrl extracts host and maps to port 8100`() {
        val result = authUrlFromTasksUrl("http://192.168.1.10:8058")
        assertEquals("http://192.168.1.10:8100", result)
    }

    @Test
    fun `authUrlFromTasksUrl returns empty for empty input`() {
        val result = authUrlFromTasksUrl("")
        assertEquals("", result)
    }

    @Test
    fun `authUrlFromTasksUrl returns empty for invalid URL`() {
        val result = authUrlFromTasksUrl("garbage")
        assertEquals("", result)
    }
}
