package com.streamvault.data.nas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NasConnectionFailure {
    INVALID_SETTINGS,
    WIFI_REQUIRED,
    AUTHENTICATION_FAILED,
    HOST_KEY_CHANGED,
    DESTINATION_UNAVAILABLE,
    DESTINATION_NOT_DIRECTORY,
    DESTINATION_NOT_WRITABLE,
    NETWORK_ERROR,
    UNKNOWN
}

data class NasConnectionTestResult(
    val success: Boolean,
    val failure: NasConnectionFailure? = null,
    val fingerprint: String? = null,
    val trustedNewHost: Boolean = false
)

/** Tests the same SFTP path that will later be used by the transfer engine. */
@Singleton
class NasSftpConnectionTester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: NasTransferSettingsStore
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }

    suspend fun testCurrent(): NasConnectionTestResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val port = settings.port.toIntOrNull()
        if (
            settings.host.isBlank() ||
            port == null || port !in 1..65535 ||
            settings.username.isBlank() ||
            settings.password.isBlank() ||
            settings.destinationPath.isBlank()
        ) {
            return@withContext NasConnectionTestResult(
                success = false,
                failure = NasConnectionFailure.INVALID_SETTINGS
            )
        }

        if (settings.wifiOnly && !hasWifiTransport()) {
            return@withContext NasConnectionTestResult(
                success = false,
                failure = NasConnectionFailure.WIFI_REQUIRED
            )
        }

        val jsch = JSch()
        val hostAlias = knownHostAlias(settings.host, port)
        if (settings.hasTrustedHostKey) {
            val knownHostsLine = buildString {
                append(hostAlias)
                append(' ')
                append(settings.trustedHostKeyType)
                append(' ')
                append(settings.trustedHostKeyBase64)
                append('\n')
            }
            jsch.setKnownHosts(ByteArrayInputStream(knownHostsLine.toByteArray(Charsets.UTF_8)))
        }

        val session = jsch.getSession(settings.username, settings.host, port)
        session.setHostKeyAlias(hostAlias)
        session.setPassword(settings.password)
        session.setConfig(
            "StrictHostKeyChecking",
            if (settings.hasTrustedHostKey) "yes" else "no"
        )
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")

        var sftp: ChannelSftp? = null
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            val connectedHostKey = session.hostKey
            val fingerprint = sha256Fingerprint(connectedHostKey.key)

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)

            val attributes = try {
                sftp.stat(settings.destinationPath)
            } catch (_: SftpException) {
                return@withContext NasConnectionTestResult(
                    success = false,
                    failure = NasConnectionFailure.DESTINATION_UNAVAILABLE,
                    fingerprint = fingerprint
                )
            }

            if (!attributes.isDir) {
                return@withContext NasConnectionTestResult(
                    success = false,
                    failure = NasConnectionFailure.DESTINATION_NOT_DIRECTORY,
                    fingerprint = fingerprint
                )
            }

            if (!verifyWritableDirectory(sftp, settings.destinationPath)) {
                return@withContext NasConnectionTestResult(
                    success = false,
                    failure = NasConnectionFailure.DESTINATION_NOT_WRITABLE,
                    fingerprint = fingerprint
                )
            }

            val trustedNewHost = !settings.hasTrustedHostKey
            if (trustedNewHost) {
                settingsStore.rememberTrustedHost(
                    keyType = connectedHostKey.type,
                    keyBase64 = connectedHostKey.key
                )
            }

            NasConnectionTestResult(
                success = true,
                fingerprint = fingerprint,
                trustedNewHost = trustedNewHost
            )
        } catch (error: JSchException) {
            NasConnectionTestResult(
                success = false,
                failure = classifyJschFailure(error, settings.hasTrustedHostKey)
            )
        } catch (_: Exception) {
            NasConnectionTestResult(
                success = false,
                failure = NasConnectionFailure.UNKNOWN
            )
        } finally {
            runCatching { sftp?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun hasWifiTransport(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun verifyWritableDirectory(sftp: ChannelSftp, destinationPath: String): Boolean {
        val remotePath = destinationPath.trimEnd('/') +
            "/.streamvault-write-test-${UUID.randomUUID()}.tmp"
        var created = false
        return try {
            ByteArrayInputStream(byteArrayOf(0x53, 0x56)).use { input ->
                sftp.put(input, remotePath, ChannelSftp.OVERWRITE)
            }
            created = true
            sftp.rm(remotePath)
            true
        } catch (_: SftpException) {
            if (created) runCatching { sftp.rm(remotePath) }
            false
        }
    }

    private fun classifyJschFailure(
        error: JSchException,
        hadTrustedHostKey: Boolean
    ): NasConnectionFailure {
        val message = error.message.orEmpty().lowercase()
        return when {
            hadTrustedHostKey && (
                "hostkey has been changed" in message ||
                    "host key has been changed" in message ||
                    "reject hostkey" in message
                ) -> NasConnectionFailure.HOST_KEY_CHANGED
            "auth fail" in message || "userauth fail" in message ->
                NasConnectionFailure.AUTHENTICATION_FAILED
            "timeout" in message ||
                "unknownhost" in message ||
                "connection refused" in message ||
                "socket" in message ||
                "network is unreachable" in message -> NasConnectionFailure.NETWORK_ERROR
            else -> NasConnectionFailure.UNKNOWN
        }
    }

    private fun knownHostAlias(host: String, port: Int): String =
        if (port == 22) host else "[$host]:$port"

    private fun sha256Fingerprint(hostKeyBase64: String): String {
        val keyBytes = Base64.decode(hostKeyBase64, Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val encoded = Base64.encodeToString(
            digest,
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "SHA256:$encoded"
    }
}
