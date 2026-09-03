package com.streamvault.data.nas

import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.repository.NasConnectionTestResult
import com.streamvault.domain.repository.NasRemoteFile
import com.streamvault.domain.repository.NasSftpClient
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpError
import com.streamvault.domain.repository.NasSftpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.FileMode.Type
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.EnumSet
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** SSHJ-backed implementation. Every call runs on Dispatchers.IO and has no UI dependency. */
@Singleton
class SshjNasSftpClient @Inject constructor() : NasSftpClient {
    override suspend fun testConnection(connection: NasSftpConnection): NasSftpResult<NasConnectionTestResult> =
        withContext(Dispatchers.IO) {
            if (connection.password.isEmpty() || connection.settings.validationErrors(requirePassword = true).isNotEmpty()) {
                return@withContext NasSftpResult.Failure(NasSftpError.INVALID_CONFIGURATION)
            }
            withSftp(connection) { sftp ->
                val directory = sftp.statExistence(connection.settings.remoteDirectory)
                    ?: return@withSftp NasSftpResult.Failure(NasSftpError.REMOTE_DIRECTORY_NOT_FOUND)
                if (directory.type != Type.DIRECTORY) {
                    return@withSftp NasSftpResult.Failure(NasSftpError.REMOTE_DIRECTORY_NOT_FOUND)
                }
                when (val writable = writeTest(sftp, connection.settings.remoteDirectory)) {
                    is NasSftpResult.Success -> NasSftpResult.Success(
                        NasConnectionTestResult(directoryVerified = true, writeVerified = writable.value)
                    )
                    is NasSftpResult.Failure -> writable
                    is NasSftpResult.HostKeyConfirmationRequired -> writable
                }
            }
        }

    override suspend fun stat(connection: NasSftpConnection, remotePath: String): NasSftpResult<NasRemoteFile?> =
        withContext(Dispatchers.IO) {
            if (remotePath.isBlank()) return@withContext NasSftpResult.Failure(NasSftpError.INVALID_CONFIGURATION)
            withSftp(connection) { sftp ->
                val remoteFile = sftp.statExistence(remotePath).toRemoteFile(remotePath)
                NasSftpResult.Success<NasRemoteFile?>(remoteFile)
            }
        }

    override suspend fun exists(connection: NasSftpConnection, remotePath: String): NasSftpResult<Boolean> =
        when (val stat = stat(connection, remotePath)) {
            is NasSftpResult.Success -> NasSftpResult.Success(stat.value != null)
            is NasSftpResult.Failure -> stat
            is NasSftpResult.HostKeyConfirmationRequired -> stat
        }

    override suspend fun getRemoteSize(connection: NasSftpConnection, remotePath: String): NasSftpResult<Long?> =
        when (val stat = stat(connection, remotePath)) {
            is NasSftpResult.Success -> NasSftpResult.Success(stat.value?.sizeBytes)
            is NasSftpResult.Failure -> stat
            is NasSftpResult.HostKeyConfirmationRequired -> stat
        }

    override suspend fun isDirectoryWritable(
        connection: NasSftpConnection,
        remoteDirectory: String
    ): NasSftpResult<Boolean> = withContext(Dispatchers.IO) {
        if (remoteDirectory.isBlank()) return@withContext NasSftpResult.Failure(NasSftpError.INVALID_CONFIGURATION)
        withSftp(connection) { sftp ->
            val attributes = sftp.statExistence(remoteDirectory)
                ?: return@withSftp NasSftpResult.Success(false)
            if (attributes.type != Type.DIRECTORY) return@withSftp NasSftpResult.Success(false)
            writeTest(sftp, remoteDirectory)
        }
    }

    private fun FileAttributes?.toRemoteFile(path: String): NasRemoteFile? = this?.let { attributes ->
        NasRemoteFile(
            path = path,
            sizeBytes = attributes.size,
            isDirectory = attributes.type == Type.DIRECTORY
        )
    }

    private fun writeTest(sftp: SFTPClient, remoteDirectory: String): NasSftpResult<Boolean> {
        val testPath = joinRemotePath(remoteDirectory, ".streamvault-write-test-${UUID.randomUUID()}.tmp")
        return try {
            sftp.open(testPath, EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE)).use { remoteFile ->
                remoteFile.write(0L, byteArrayOf(0), 0, 1)
            }
            try {
                sftp.rm(testPath)
                NasSftpResult.Success(true)
            } catch (_: Exception) {
                NasSftpResult.Failure(NasSftpError.REMOTE_TEST_CLEANUP_FAILED)
            }
        } catch (_: SFTPException) {
            NasSftpResult.Failure(NasSftpError.REMOTE_DIRECTORY_NOT_WRITABLE)
        } catch (_: Exception) {
            NasSftpResult.Failure(NasSftpError.REMOTE_DIRECTORY_NOT_WRITABLE)
        }
    }

    private fun joinRemotePath(directory: String, child: String): String =
        if (directory.endsWith('/')) "$directory$child" else "$directory/$child"

    private fun <T> withSftp(
        connection: NasSftpConnection,
        block: (SFTPClient) -> NasSftpResult<T>
    ): NasSftpResult<T> {
        val keyVerifier = PinnedHostKeyVerifier(connection.trustedHostKey)
        var stage = ConnectionStage.CONNECT
        return try {
            SSHClient().use { ssh ->
                ssh.connectTimeout = CONNECT_TIMEOUT_MS
                ssh.timeout = SOCKET_TIMEOUT_MS
                ssh.addHostKeyVerifier(keyVerifier)
                ssh.connect(connection.settings.host, connection.settings.port)
                stage = ConnectionStage.AUTHENTICATE
                ssh.authPassword(connection.settings.username, connection.password)
                stage = ConnectionStage.OPEN_SFTP
                ssh.newSFTPClient().use(block)
            }
        } catch (error: Exception) {
            keyVerifier.observedTrust?.takeIf { keyVerifier.verificationRejected }?.let { observed ->
                return if (connection.trustedHostKey == null) {
                    NasSftpResult.HostKeyConfirmationRequired(observed)
                } else {
                    NasSftpResult.Failure(NasSftpError.HOST_KEY_CHANGED)
                }
            }
            NasSftpResult.Failure(error.toDomainError(stage))
        }
    }

    private fun Exception.toDomainError(stage: ConnectionStage): NasSftpError {
        val causes = generateSequence(this as Throwable) { it.cause }.toList()
        return when {
            causes.any { it is ConnectException } -> NasSftpError.CONNECTION_REFUSED
            causes.any { it is UnknownHostException || it is SocketException } -> NasSftpError.DNS_OR_HOST_UNREACHABLE
            causes.any { it is SocketTimeoutException } -> NasSftpError.TIMEOUT
            causes.any { it is UserAuthException } -> NasSftpError.AUTHENTICATION_FAILED
            causes.any { it is SFTPException } && stage == ConnectionStage.OPEN_SFTP ->
                NasSftpError.REMOTE_DIRECTORY_NOT_FOUND
            else -> NasSftpError.UNKNOWN
        }
    }

    private enum class ConnectionStage {
        CONNECT,
        AUTHENTICATE,
        OPEN_SFTP
    }

    /** Rejects all unknown keys, while retaining the fingerprint solely for an explicit UI prompt. */
    private class PinnedHostKeyVerifier(
        private val expected: NasHostKeyTrust?
    ) : HostKeyVerifier {
        var observedTrust: NasHostKeyTrust? = null
            private set
        var verificationRejected: Boolean = false
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val candidate = NasHostKeyTrust(
                host = hostname,
                port = port,
                algorithm = key.algorithm,
                fingerprint = SecurityUtils.getFingerprint(key)
            )
            observedTrust = candidate
            val accepted = expected?.let { trusted ->
                trusted.host == hostname && trusted.port == port &&
                    trusted.algorithm == candidate.algorithm && trusted.fingerprint == candidate.fingerprint
            } ?: false
            verificationRejected = !accepted
            return accepted
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
