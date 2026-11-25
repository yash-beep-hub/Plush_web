package com.example.browser

import android.net.Uri

object UrlUtils {
    const val DEFAULT_INTERVAL_SECONDS = 10
    const val MIN_INTERVAL_SECONDS = 1
    const val MAX_INTERVAL_SECONDS = 86_400

    /**
     * Normalizes a user-entered URL by trimming whitespace and prepending https://
     * whenever an explicit scheme (http://, https://, about:, file://) is missing.
     * Returns null if the input is blank.
     */
    fun normalizeUrl(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val hasScheme = lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            lower.startsWith("about:") ||
            lower.startsWith("file://")

        return if (hasScheme) trimmed else "https://$trimmed"
    }

    /**
     * Parses a user-entered interval string in seconds, returning null if invalid or < 1.
     */
    fun parseIntervalSeconds(rawInput: String): Int? {
        val parsed = rawInput.trim().toIntOrNull() ?: return null
        if (parsed < MIN_INTERVAL_SECONDS) return null
        return parsed.coerceAtMost(MAX_INTERVAL_SECONDS)
    }

    /**
     * Returns a compact host/label representation of a URL for notifications and status chips.
     */
    fun compactDisplayHost(url: String): String {
        if (url.isBlank()) return "No page loaded"
        return runCatching {
            val uri = Uri.parse(url)
            val host = uri.host?.removePrefix("www.")
            if (!host.isNullOrBlank()) host else url
        }.getOrDefault(url)
    }
}
