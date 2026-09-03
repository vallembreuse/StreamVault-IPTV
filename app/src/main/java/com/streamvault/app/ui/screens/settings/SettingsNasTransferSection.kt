package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.NasLocalFilePolicy
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasSftpError

internal fun LazyListScope.settingsNasTransferSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val settings = uiState.nasTransferSettings

    item {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_nas_transfer_title),
            subtitle = stringResource(R.string.settings_nas_transfer_subtitle)
        )
    }
    item {
        SwitchSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_enable),
            value = stringResource(
                if (settings.enabled) R.string.settings_nas_transfer_enabled else R.string.settings_nas_transfer_disabled
            ),
            checked = settings.enabled,
            onCheckedChange = viewModel::setNasTransferEnabled
        )
    }
    item {
        var showConfiguration by remember { mutableStateOf(false) }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_configuration),
            value = settings.host.ifBlank { stringResource(R.string.settings_nas_transfer_not_configured) },
            onClick = { showConfiguration = true }
        )
        if (showConfiguration) {
            NasTransferConfigurationDialog(
                initialSettings = settings,
                passwordConfigured = uiState.nasPasswordConfigured,
                onDismiss = { showConfiguration = false },
                onSave = { updated, password ->
                    viewModel.saveNasTransferSettings(updated, password)
                    showConfiguration = false
                }
            )
        }
    }
    item {
        SettingsRow(
            label = stringResource(R.string.settings_nas_transfer_authentication),
            value = stringResource(R.string.settings_nas_transfer_authentication_password)
        )
    }
    item {
        SettingsRow(
            label = stringResource(R.string.settings_nas_transfer_password),
            value = stringResource(
                if (uiState.nasPasswordConfigured) {
                    R.string.settings_nas_transfer_password_configured
                } else {
                    R.string.settings_nas_transfer_password_missing
                }
            )
        )
    }
    item {
        SwitchSettingsRow(
            label = stringResource(R.string.settings_nas_transfer_delete_after_success),
            value = stringResource(R.string.settings_nas_transfer_delete_after_success_hint),
            checked = settings.localFilePolicy == NasLocalFilePolicy.DELETE_AFTER_VALIDATED_TRANSFER,
            onCheckedChange = viewModel::setNasLocalFilePolicyDeleteAfterValidatedTransfer
        )
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                onClick = viewModel::testNasConnection,
                enabled = uiState.nasConnectionTest !is NasConnectionTestUiState.Running
            ) {
                Text(stringResource(R.string.settings_nas_transfer_test_connection))
            }
            NasConnectionTestMessage(
                state = uiState.nasConnectionTest,
                onTrustHostKey = viewModel::confirmNasHostKeyAndTest
            )
        }
    }

}

@Composable
private fun NasConnectionTestMessage(
    state: NasConnectionTestUiState,
    onTrustHostKey: () -> Unit
) {
    when (state) {
        NasConnectionTestUiState.Idle -> Unit
        NasConnectionTestUiState.Running -> Text(
            text = stringResource(R.string.settings_nas_transfer_test_running),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim
        )
        NasConnectionTestUiState.Success -> Text(
            text = stringResource(R.string.settings_nas_transfer_test_success),
            style = MaterialTheme.typography.bodyMedium,
            color = Primary
        )
        is NasConnectionTestUiState.HostKeyConfirmationRequired -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.settings_nas_transfer_host_key_confirmation,
                    state.algorithm,
                    state.fingerprint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            TvButton(onClick = onTrustHostKey) {
                Text(stringResource(R.string.settings_nas_transfer_trust_host_key))
            }
        }
        is NasConnectionTestUiState.Failure -> Text(
            text = nasSftpErrorLabel(state.error),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun nasSftpErrorLabel(error: NasSftpError): String = stringResource(
    when (error) {
        NasSftpError.INVALID_CONFIGURATION -> R.string.settings_nas_transfer_error_invalid_configuration
        NasSftpError.DNS_OR_HOST_UNREACHABLE -> R.string.settings_nas_transfer_error_host_unreachable
        NasSftpError.CONNECTION_REFUSED -> R.string.settings_nas_transfer_error_connection_refused
        NasSftpError.HOST_KEY_UNKNOWN -> R.string.settings_nas_transfer_error_host_key_unknown
        NasSftpError.HOST_KEY_CHANGED -> R.string.settings_nas_transfer_error_host_key_changed
        NasSftpError.AUTHENTICATION_FAILED -> R.string.settings_nas_transfer_error_authentication
        NasSftpError.REMOTE_DIRECTORY_NOT_FOUND -> R.string.settings_nas_transfer_error_remote_directory
        NasSftpError.REMOTE_DIRECTORY_NOT_WRITABLE -> R.string.settings_nas_transfer_error_remote_directory_writable
        NasSftpError.REMOTE_TEST_CLEANUP_FAILED -> R.string.settings_nas_transfer_error_cleanup
        NasSftpError.TIMEOUT -> R.string.settings_nas_transfer_error_timeout
        NasSftpError.UNKNOWN -> R.string.settings_nas_transfer_error_unknown
    }
)

@Composable
private fun NasTransferConfigurationDialog(
    initialSettings: NasTransferSettings,
    passwordConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (NasTransferSettings, String) -> Unit
) {
    var host by remember(initialSettings) { mutableStateOf(initialSettings.host) }
    var port by remember(initialSettings) { mutableStateOf(initialSettings.port.toString()) }
    var username by remember(initialSettings) { mutableStateOf(initialSettings.username) }
    var remoteDirectory by remember(initialSettings) { mutableStateOf(initialSettings.remoteDirectory) }
    // Never initialise this field from stored credentials. Blank means “retain the saved secret”.
    var password by remember { mutableStateOf("") }

    PremiumDialog(
        title = stringResource(R.string.settings_nas_transfer_configuration),
        subtitle = stringResource(R.string.settings_nas_transfer_configuration_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.5f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                EpgSourceTextField(host, { host = it }, stringResource(R.string.settings_nas_transfer_host))
                EpgSourceTextField(port, { port = it }, stringResource(R.string.settings_nas_transfer_port))
                EpgSourceTextField(username, { username = it }, stringResource(R.string.settings_nas_transfer_username))
                EpgSourceTextField(remoteDirectory, { remoteDirectory = it }, stringResource(R.string.settings_nas_transfer_remote_directory))
                EpgSourceTextField(
                    password,
                    { password = it },
                    stringResource(
                        if (passwordConfigured) R.string.settings_nas_transfer_password_keep else R.string.settings_nas_transfer_password
                    ),
                    obscureText = true
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(label = stringResource(R.string.action_cancel), onClick = onDismiss)
            PremiumDialogFooterButton(
                label = stringResource(R.string.action_save),
                onClick = {
                    onSave(
                        initialSettings.copy(
                            host = host,
                            port = port.toIntOrNull() ?: 0,
                            username = username,
                            remoteDirectory = remoteDirectory
                        ),
                        password
                    )
                }
            )
        }
    )
}
