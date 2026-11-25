package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PulseWeb", appName)
    }

    @Test
    fun `normalizeUrl auto prepends https when scheme missing`() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("example.com"))
        assertEquals("https://example.com/login", UrlUtils.normalizeUrl("  example.com/login  "))
        assertEquals("http://localhost:8080", UrlUtils.normalizeUrl("http://localhost:8080"))
        assertEquals("https://time.is", UrlUtils.normalizeUrl("https://time.is"))
        assertNull(UrlUtils.normalizeUrl("   "))
    }

    @Test
    fun `parseIntervalSeconds validates positive bounds`() {
        assertEquals(10, UrlUtils.parseIntervalSeconds("10"))
        assertEquals(1, UrlUtils.parseIntervalSeconds("1"))
        assertEquals(UrlUtils.MAX_INTERVAL_SECONDS, UrlUtils.parseIntervalSeconds("999999"))
        assertNull(UrlUtils.parseIntervalSeconds("0"))
        assertNull(UrlUtils.parseIntervalSeconds("-5"))
        assertNull(UrlUtils.parseIntervalSeconds("abc"))
    }
}
