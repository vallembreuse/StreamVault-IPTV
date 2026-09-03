package com.streamvault.domain.repository

import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.model.NasTransferSettings

/** In-memory connection material. Do not log or persist instances of this class. */
class NasSftpConnection(
    val settings: NasTransferSettings,
    val password: CharArray,
    val trustedHostKey: NasHostKeyTrust?
)

data class NasRemoteFile(
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

enum class NasSftpError {
    INVALID_CONFIGURATION,
    DNS_OR_HOST_UNREACHABLE,
    CONNECTION_REFUSED,
    HOST_KEY_UNKNOWN,
    HOST_KEY_CHANGED,
    AUTHENTICATION_FAILED,
    REMOTE_DIRECTORY_NOT_FOUND,
    REMOTE_DIRECTORY_NOT_WRITABLE,
    REMOTE_TEST_CLEANUP_FAILED,
    TIMEOUT,
    UNKNOWN
}

sealed interface NasSftpResult<out T> {
    data class Success<T>(val value: T) : NasSftpResult<T>
    data class Failure(val error: NasSftpError) : NasSftpResult<Nothing>
    data class HostKeyConfirmationRequired(val trust: NasHostKeyTrust) : NasSftpResult<Nothing>
}

data class NasConnectionTestResult(
    val directoryVerified: Boolean,
    val writeVerified: Boolean
)

/**
 * The Lot 1 boundary for SSH/SFTP. Upload, rename, and resume are deliberately deferred to Lot 2.
 */
interface NasSftpClient {
    suspend fun testConnection(connection: NasSftpConnection): NasSftpResult<NasConnectionTestResult>

    suspend fun stat(connection: NasSftpConnection, remotePath: String): NasSftpResult<NasRemoteFile?>

    suspend fun exists(connection: NasSftpConnection, remotePath: String): NasSftpResult<Boolean>

    suspend fun getRemoteSize(connection: NasSftpConnection, remotePath: String): NasSftpResult<Long?>

    suspend fun isDirectoryWritable(connection: NasSftpConnection, remoteDirectory: String): NasSftpResult<Boolean>
}
