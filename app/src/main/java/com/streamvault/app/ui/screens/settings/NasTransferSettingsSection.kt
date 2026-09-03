package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.data.nas.NasConnectionFailure
import com.streamvault.data.nas.NasConnectionTestResult
import com.streamvault.data.nas.NasSftpConnectionTester
import com.streamvault.data.nas.NasTransferSettings
import com.streamvault.data.nas.NasTransferSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NasTransferSettingsUiState(
    val settings: NasTransferSettings = NasTransferSettings(),
    val isTesting: Boolean = false,
    val testResult: NasConnectionTestResult? = null
)

private data class ConnectionTestState(
    val isTesting: Boolean = false,
    val result: NasConnectionTestResult? = null
)

@HiltViewModel
internal class NasTransferSettingsViewModel @Inject constructor(
    private val settingsStore: NasTransferSettingsStore,
    private val connectionTester: NasSftpConnectionTester
) : ViewModel() {
    private val connectionTestState = MutableStateFlow(ConnectionTestState())

    val uiState: StateFlow<NasTransferSettingsUiState> = combine(
        settingsStore.settings,
        connectionTestState
    ) { settings, testState ->
        NasTransferSettingsUiState(
            settings = settings,
            isTesting = testState.isTesting,
            testResult = testState.result
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NasTransferSettingsUiState(settings = settingsStore.current())
    )

    fun setEnabled(value: Boolean) = updateSettings { settingsStore.setEnabled(value) }
    fun setHost(value: String) = updateSettings { settingsStore.setHost(value) }
    fun setPort(value: String) = updateSettings { settingsStore.setPort(value) }
    fun setUsername(value: String) = updateSettings { settingsStore.setUsername(value) }
    fun setPassword(value: String) = updateSettings { settingsStore.setPassword(value) }
    fun setDestinationPath(value: String) = updateSettings { settingsStore.setDestinationPath(value) }
    fun setWifiOnly(value: Boolean) = updateSettings { settingsStore.setWifiOnly(value) }
    fun setDeleteAfterTransfer(value: Boolean) = updateSettings {
        settingsStore.setDeleteAfterTransfer(value)
    }

    fun testConnection() {
        if (connectionTestState.value.isTesting) return
        connectionTestState.value = ConnectionTestState(isTesting = true)
        viewModelScope.launch {
            val result = connectionTester.testCurrent()
            connectionTestState.value = ConnectionTestState(result = result)
        }
    }

    fun clearTrustedHost() {
        settingsStore.clearTrustedHost()
        clearTestFeedback()
    }

    private fun updateSettings(update: () -> Unit) {
        clearTestFeedback()
        update()
    }

    private fun clearTestFeedback() {
        connectionTestState.update { ConnectionTestState() }
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.settings
    val portIsValid = state.port.toIntOrNull()?.let { it in 1..65535 } == true
    val canTest = state.enabled &&
        state.secureStorageAvailable &&
        state.host.isNotBlank() &&
        portIsValid &&
        state.username.isNotBlank() &&
        state.password.isNotBlank() &&
        state.destinationPath.isNotBlank() &&
        !uiState.isTesting

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

        Text(
            text = stringResource(R.string.settings_nas_transfer_host_trust_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        TvButton(
            onClick = viewModel::testConnection,
            enabled = canTest
        ) {
            Text(
                text = stringResource(
                    if (uiState.isTesting) {
                        R.string.settings_nas_transfer_testing
                    } else {
                        R.string.settings_nas_transfer_test_connection
                    }
                )
            )
        }

        uiState.testResult?.let { result ->
            NasConnectionTestFeedback(result = result)
        }

        if (state.hasTrustedHostKey) {
            TvButton(onClick = viewModel::clearTrustedHost) {
                Text(text = stringResource(R.string.settings_nas_transfer_forget_host_key))
            }
        }

        if (!state.secureStorageAvailable) {
            Text(
                text = stringResource(R.string.settings_nas_transfer_secure_storage_unavailable),
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NasConnectionTestFeedback(result: NasConnectionTestResult) {
    val message = if (result.success) {
        stringResource(
            if (result.trustedNewHost) {
                R.string.settings_nas_transfer_test_success_trusted
            } else {
                R.string.settings_nas_transfer_test_success
            }
        )
    } else {
        stringResource(
            when (result.failure) {
                NasConnectionFailure.INVALID_SETTINGS -> R.string.settings_nas_transfer_test_invalid
                NasConnectionFailure.WIFI_REQUIRED -> R.string.settings_nas_transfer_test_wifi_required
                NasConnectionFailure.AUTHENTICATION_FAILED -> R.string.settings_nas_transfer_test_auth_failed
                NasConnectionFailure.HOST_KEY_CHANGED -> R.string.settings_nas_transfer_test_host_key_changed
                NasConnectionFailure.DESTINATION_UNAVAILABLE -> R.string.settings_nas_transfer_test_destination_unavailable
                NasConnectionFailure.DESTINATION_NOT_DIRECTORY -> R.string.settings_nas_transfer_test_destination_not_directory
                NasConnectionFailure.DESTINATION_NOT_WRITABLE -> R.string.settings_nas_transfer_test_destination_not_writable
                NasConnectionFailure.NETWORK_ERROR -> R.string.settings_nas_transfer_test_network_error
                NasConnectionFailure.UNKNOWN,
                null -> R.string.settings_nas_transfer_test_unknown
            }
        )
    }

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.success) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        result.fingerprint?.let { fingerprint ->
            Text(
                text = stringResource(
                    R.string.settings_nas_transfer_host_fingerprint,
                    fingerprint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth()
    )
}
