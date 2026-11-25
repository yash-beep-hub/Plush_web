package com.example

import com.example.browser.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun normalizeUrl_prependsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("example.com"))
        assertEquals("https://example.com/login", UrlUtils.normalizeUrl("  example.com/login  "))
        assertEquals("http://localhost:8080", UrlUtils.normalizeUrl("http://localhost:8080"))
        assertEquals("https://time.is", UrlUtils.normalizeUrl("https://time.is"))
        assertNull(UrlUtils.normalizeUrl("   "))
    }

    @Test
    fun parseIntervalSeconds_validatesPositiveBounds() {
        assertEquals(10, UrlUtils.parseIntervalSeconds("10"))
        assertEquals(1, UrlUtils.parseIntervalSeconds("1"))
        assertEquals(UrlUtils.MAX_INTERVAL_SECONDS, UrlUtils.parseIntervalSeconds("999999"))
        assertNull(UrlUtils.parseIntervalSeconds("0"))
        assertNull(UrlUtils.parseIntervalSeconds("-5"))
        assertNull(UrlUtils.parseIntervalSeconds("abc"))
    }
}
