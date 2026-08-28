package com.lansync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A file that failed to sync, kept so it can be retried or dismissed later. */
data class FailedSyncEntry(
    val uri: String,
    val relativePath: String?,
    val displayName: String,
    val errorMessage: String,
    val timestamp: Long
)

/**
 * Persists failed sync attempts across app restarts via SharedPreferences, so a
 * dropped connection or a locked file doesn't lose track of what still needs to sync.
 */
class FailedSyncStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<FailedSyncEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                if (uri.isBlank()) return@mapNotNull null
                FailedSyncEntry(
                    uri = uri,
                    relativePath = o.optString("relativePath").ifBlank { null },
                    displayName = o.optString("displayName").ifBlank { uri },
                    errorMessage = o.optString("errorMessage").ifBlank { "Failed" },
                    timestamp = o.optLong("timestamp")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(entries: List<FailedSyncEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject().apply {
                    put("uri", entry.uri)
                    put("relativePath", entry.relativePath ?: "")
                    put("displayName", entry.displayName)
                    put("errorMessage", entry.errorMessage)
                    put("timestamp", entry.timestamp)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "failed_syncs"
        private const val KEY_ENTRIES = "entries"
    }
}
