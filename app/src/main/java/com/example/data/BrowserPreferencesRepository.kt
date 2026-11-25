package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.browser.UrlUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.browserPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pulseweb_browser_prefs"
)

data class SavedBrowserConfig(
    val lastUrl: String = "",
    val intervalSeconds: Int = UrlUtils.DEFAULT_INTERVAL_SECONDS
)

class BrowserPreferencesRepository(private val context: Context) {

    private object Keys {
        val LAST_URL = stringPreferencesKey("last_url")
        val INTERVAL_SECONDS = intPreferencesKey("interval_seconds")
    }

    val savedConfig: Flow<SavedBrowserConfig> =
        context.browserPreferencesDataStore.data.map { prefs ->
            SavedBrowserConfig(
                lastUrl = prefs[Keys.LAST_URL].orEmpty(),
                intervalSeconds = prefs[Keys.INTERVAL_SECONDS] ?: UrlUtils.DEFAULT_INTERVAL_SECONDS
            )
        }

    suspend fun saveLastUrl(url: String) {
        context.browserPreferencesDataStore.edit { prefs ->
            prefs[Keys.LAST_URL] = url
        }
    }

    suspend fun saveIntervalSeconds(seconds: Int) {
        context.browserPreferencesDataStore.edit { prefs ->
            prefs[Keys.INTERVAL_SECONDS] = seconds.coerceIn(
                UrlUtils.MIN_INTERVAL_SECONDS,
                UrlUtils.MAX_INTERVAL_SECONDS
            )
        }
    }
}
