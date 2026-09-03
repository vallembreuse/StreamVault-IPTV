package com.streamvault.data.nas

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent NAS transfer configuration shared by settings UI and transfer workers/services. */
data class NasTransferSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val destinationPath: String = DEFAULT_DESTINATION_PATH,
    val wifiOnly: Boolean = true,
    val deleteAfterTransfer: Boolean = false,
    val trustedHostKeyType: String? = null,
    val trustedHostKeyBase64: String? = null,
    val secureStorageAvailable: Boolean = true
) {
    val hasTrustedHostKey: Boolean
        get() = !trustedHostKeyType.isNullOrBlank() && !trustedHostKeyBase64.isNullOrBlank()

    companion object {
        const val DEFAULT_DESTINATION_PATH = "/volume1/Media/Films/"
    }
}

@Singleton
class NasTransferSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private companion object {
        const val PREFS_NAME = "nas_transfer_settings"
        const val SECURE_PREFS_NAME = "nas_transfer_secure"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_DESTINATION = "destination"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_DELETE_AFTER_TRANSFER = "delete_after_transfer"
        const val KEY_PASSWORD = "password"
        const val KEY_TRUSTED_HOST_KEY_TYPE = "trusted_host_key_type"
        const val KEY_TRUSTED_HOST_KEY_BASE64 = "trusted_host_key_base64"
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePreferences: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<NasTransferSettings> = _settings.asStateFlow()

    fun current(): NasTransferSettings = _settings.value

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        publish()
    }

    fun setHost(value: String) {
        val normalized = value.trim()
        val hostChanged = normalized != _settings.value.host
        preferences.edit().putString(KEY_HOST, normalized).apply()
        if (hostChanged) clearTrustedHostInternal()
        publish()
    }

    fun setPort(value: String) {
        if (value.isNotEmpty() && value.any { !it.isDigit() }) return
        val portChanged = value != _settings.value.port
        preferences.edit().putString(KEY_PORT, value).apply()
        if (portChanged) clearTrustedHostInternal()
        publish()
    }

    fun setUsername(value: String) {
        preferences.edit().putString(KEY_USERNAME, value.trim()).apply()
        publish()
    }

    fun setPassword(value: String) {
        val secure = securePreferences ?: return
        secure.edit().putString(KEY_PASSWORD, value).apply()
        publish()
    }

    fun setDestinationPath(value: String) {
        preferences.edit().putString(KEY_DESTINATION, value.trim()).apply()
        publish()
    }

    fun setWifiOnly(value: Boolean) {
        preferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        publish()
    }

    fun setDeleteAfterTransfer(value: Boolean) {
        preferences.edit().putBoolean(KEY_DELETE_AFTER_TRANSFER, value).apply()
        publish()
    }

    fun rememberTrustedHost(keyType: String, keyBase64: String) {
        preferences.edit()
            .putString(KEY_TRUSTED_HOST_KEY_TYPE, keyType)
            .putString(KEY_TRUSTED_HOST_KEY_BASE64, keyBase64)
            .apply()
        publish()
    }

    fun clearTrustedHost() {
        clearTrustedHostInternal()
        publish()
    }

    private fun clearTrustedHostInternal() {
        preferences.edit()
            .remove(KEY_TRUSTED_HOST_KEY_TYPE)
            .remove(KEY_TRUSTED_HOST_KEY_BASE64)
            .apply()
    }

    private fun publish() {
        _settings.value = loadSettings()
    }

    private fun loadSettings(): NasTransferSettings = NasTransferSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        host = preferences.getString(KEY_HOST, "").orEmpty(),
        port = preferences.getString(KEY_PORT, "22").orEmpty().ifBlank { "22" },
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        password = securePreferences?.getString(KEY_PASSWORD, "").orEmpty(),
        destinationPath = preferences.getString(
            KEY_DESTINATION,
            NasTransferSettings.DEFAULT_DESTINATION_PATH
        ).orEmpty().ifBlank { NasTransferSettings.DEFAULT_DESTINATION_PATH },
        wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true),
        deleteAfterTransfer = preferences.getBoolean(KEY_DELETE_AFTER_TRANSFER, false),
        trustedHostKeyType = preferences.getString(KEY_TRUSTED_HOST_KEY_TYPE, null),
        trustedHostKeyBase64 = preferences.getString(KEY_TRUSTED_HOST_KEY_BASE64, null),
        secureStorageAvailable = securePreferences != null
    )
}
