package com.yugahashimoto.andcode.data.browser

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers the page the guest browser was last on, so reopening it resumes there instead of
 * starting blank. Sign-ins are not stored here: they live in the WebView's own cookie store and
 * keep working on their own.
 */
class GuestBrowserLastUrl(
    private val preferences: SharedPreferences,
) {
    /** The last web page visited, or null before one has been visited. */
    fun load(): String? = stored()?.takeIf(::isWebUrl)

    /** Keeps [url] as the page to resume; non-web pages leave the previous one in place. */
    @Synchronized
    fun save(url: String?) {
        val value = url?.takeIf(::isWebUrl) ?: return
        if (value == stored()) return
        preferences.edit().putString(KEY_URL, value).apply()
    }

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    @Synchronized
    private fun stored(): String? = runCatching { preferences.getString(KEY_URL, null) }.getOrNull()

    companion object {
        internal const val PREFS_NAME = "guest_browser"
        internal const val KEY_URL = "last_url"

        fun create(context: Context): GuestBrowserLastUrl =
            GuestBrowserLastUrl(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
