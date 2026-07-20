package com.lansync

import android.content.Context
import com.lansync.network.ServerConfig

/**
 * Persists connection fields across app restarts via SharedPreferences.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): SavedSettings = SavedSettings(
        host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST,
        port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
        username = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME,
        deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
    )

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_DEVICE_NAME, config.deviceName.trim())
            .apply()
    }

    fun save(
        host: String,
        port: Int,
        username: String,
        deviceName: String
    ) {
        save(ServerConfig(host.trim(), port, username.trim(), deviceName.trim()))
    }

    companion object {
        private const val PREFS_NAME = "sync_settings"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_DEVICE_NAME = "device_name"

        const val DEFAULT_HOST = "192.168.0.195"
        const val DEFAULT_PORT = 10101
        const val DEFAULT_USERNAME = "user1"
        const val DEFAULT_DEVICE_NAME = "android-phone"
    }
}

data class SavedSettings(
    val host: String,
    val port: Int,
    val username: String,
    val deviceName: String
) {
    fun toServerConfig(): ServerConfig = ServerConfig(host, port, username, deviceName)
}
