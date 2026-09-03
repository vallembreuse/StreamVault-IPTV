package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamvault.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NasTransferSettingsUiState(
    val enabled: Boolean = false,
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val destinationPath: String = "/volume1/Media/Films/",
    val wifiOnly: Boolean = true,
    val deleteAfterTransfer: Boolean = false,
    val secureStorageAvailable: Boolean = true
)

@HiltViewModel
internal class NasTransferSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
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
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePreferences = runCatching {
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

    private val _uiState = MutableStateFlow(
        NasTransferSettingsUiState(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            host = preferences.getString(KEY_HOST, "").orEmpty(),
            port = preferences.getString(KEY_PORT, "22").orEmpty().ifBlank { "22" },
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = securePreferences?.getString(KEY_PASSWORD, "").orEmpty(),
            destinationPath = preferences.getString(KEY_DESTINATION, "/volume1/Media/Films/")
                .orEmpty()
                .ifBlank { "/volume1/Media/Films/" },
            wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true),
            deleteAfterTransfer = preferences.getBoolean(KEY_DELETE_AFTER_TRANSFER, false),
            secureStorageAvailable = securePreferences != null
        )
    )
    val uiState: StateFlow<NasTransferSettingsUiState> = _uiState.asStateFlow()

    fun setEnabled(value: Boolean) = updatePreference(KEY_ENABLED, value) { copy(enabled = value) }
    fun setHost(value: String) = updatePreference(KEY_HOST, value.trim()) { copy(host = value) }
    fun setPort(value: String) {
        if (value.isNotEmpty() && value.any { !it.isDigit() }) return
        updatePreference(KEY_PORT, value) { copy(port = value) }
    }
    fun setUsername(value: String) = updatePreference(KEY_USERNAME, value.trim()) { copy(username = value) }
    fun setDestinationPath(value: String) = updatePreference(KEY_DESTINATION, value.trim()) { copy(destinationPath = value) }
    fun setWifiOnly(value: Boolean) = updatePreference(KEY_WIFI_ONLY, value) { copy(wifiOnly = value) }
    fun setDeleteAfterTransfer(value: Boolean) = updatePreference(KEY_DELETE_AFTER_TRANSFER, value) {
        copy(deleteAfterTransfer = value)
    }

    fun setPassword(value: String) {
        _uiState.update { it.copy(password = value) }
        val securePrefs = securePreferences ?: return
        viewModelScope.launch {
            securePrefs.edit().putString(KEY_PASSWORD, value).apply()
        }
    }

    private fun updatePreference(
        key: String,
        value: Any,
        transform: NasTransferSettingsUiState.() -> NasTransferSettingsUiState
    ) {
        _uiState.update { it.transform() }
        viewModelScope.launch {
            preferences.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }.apply()
        }
    }
}

internal fun LazyListScope.settingsNasTransferSection() {
    item {
        NasTransferSettingsPanel()
    }
}

@Composable
private fun NasTransferSettingsPanel(
    viewModel: NasTransferSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_nas_transfer_title),
            subtitle = stringResource(R.string.settings_nas_transfer_subtitle)
        )

        SwitchSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_enabled),
            value = stringResource(
                if (state.enabled) R.string.settings_enabled else R.string.settings_nas_transfer_disabled
            ),
            checked = state.enabled,
            onCheckedChange = viewModel::setEnabled
        )

        NasSettingsTextField(
            value = state.host,
            onValueChange = viewModel::setHost,
            label = stringResource(R.string.settings_nas_transfer_host),
            enabled = state.enabled
        )
        NasSettingsTextField(
            value = state.port,
            onValueChange = viewModel::setPort,
            label = stringResource(R.string.settings_nas_transfer_port),
            enabled = state.enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        NasSettingsTextField(
            value = state.username,
            onValueChange = viewModel::setUsername,
            label = stringResource(R.string.settings_nas_transfer_username),
            enabled = state.enabled
        )
        NasSettingsTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = stringResource(R.string.settings_nas_transfer_password),
            enabled = state.enabled && state.secureStorageAvailable,
            password = true
        )
        NasSettingsTextField(
            value = state.destinationPath,
            onValueChange = viewModel::setDestinationPath,
            label = stringResource(R.string.settings_nas_transfer_destination),
            enabled = state.enabled
        )

        SwitchSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_wifi_only),
            value = stringResource(R.string.settings_nas_transfer_wifi_only_desc),
            checked = state.wifiOnly,
            onCheckedChange = viewModel::setWifiOnly,
            enabled = state.enabled
        )
        SwitchSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_delete_after),
            value = stringResource(R.string.settings_nas_transfer_delete_after_desc),
            checked = state.deleteAfterTransfer,
            onCheckedChange = viewModel::setDeleteAfterTransfer,
            enabled = state.enabled
        )

        if (!state.secureStorageAvailable) {
            Text(
                text = stringResource(R.string.settings_nas_transfer_secure_storage_unavailable),
                modifier = Modifier.padding(horizontal = 8.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NasSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}
