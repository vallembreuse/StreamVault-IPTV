package com.streamvault.domain.manager

import com.streamvault.domain.model.NasPublicationFailureReason.*
import com.streamvault.domain.model.NasPublicationResult
import com.streamvault.domain.repository.NasRemoteFile
import com.streamvault.domain.repository.NasSftpClient
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpError
import com.streamvault.domain.repository.NasSftpResult
import com.streamvault.domain.repository.NasTransferSource
import javax.inject.Inject

/** Publishes once without cleanup or resume. Connection credentials remain owned by the caller. */
class NasFilePublisher @Inject constructor(
    private val sftpClient: NasSftpClient
) {
    suspend fun publish(
        connection: NasSftpConnection,
        source: NasTransferSource,
        finalPath: String
    ): NasPublicationResult =
        publish(connection, source, finalPath) { _ -> }

    suspend fun publish(
        connection: NasSftpConnection,
        source: NasTransferSource,
        finalPath: String,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): NasPublicationResult {
        val expectedSize = source.sizeBytes
        if (expectedSize < 0L || finalPath.isBlank()) {
            return NasPublicationResult.Failure(INVALID_INPUT)
        }
        val partPath = "$finalPath.part"

        val initialFinal = when (val result = sftpClient.stat(connection, finalPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        existingFinalResult(initialFinal, expectedSize)?.let { return it }

        val partExists = when (val result = sftpClient.exists(connection, partPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        if (partExists) return NasPublicationResult.Failure(PART_ALREADY_EXISTS)

        val uploadedBytes = when (
            val result = sftpClient.upload(connection, source, partPath, onProgress)
        ) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        if (uploadedBytes != expectedSize) return NasPublicationResult.Failure(SIZE_MISMATCH)

        val partSize = when (val result = sftpClient.getRemoteSize(connection, partPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        if (partSize != expectedSize) return NasPublicationResult.Failure(SIZE_MISMATCH)

        val finalBeforeRename = when (val result = sftpClient.stat(connection, finalPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        existingFinalResult(finalBeforeRename, expectedSize)?.let { return it }

        when (val result = sftpClient.rename(connection, partPath, finalPath)) {
            is NasSftpResult.Success -> Unit
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }

        val finalSize = when (val result = sftpClient.getRemoteSize(connection, finalPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        if (finalSize == null) return NasPublicationResult.Failure(FINAL_MISSING)
        if (finalSize != expectedSize) return NasPublicationResult.Failure(SIZE_MISMATCH)

        val remainingPart = when (val result = sftpClient.exists(connection, partPath)) {
            is NasSftpResult.Success -> result.value
            is NasSftpResult.Failure -> return NasPublicationResult.Failure(SFTP_FAILURE, result.error)
            is NasSftpResult.HostKeyConfirmationRequired ->
                return NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
        }
        if (remainingPart) return NasPublicationResult.Failure(PART_STILL_PRESENT)

        return NasPublicationResult.Success
    }

    private fun existingFinalResult(file: NasRemoteFile?, expectedSize: Long): NasPublicationResult? = when {
        file == null -> null
        file.isDirectory -> NasPublicationResult.Conflict
        file.sizeBytes == expectedSize -> NasPublicationResult.AlreadyPresent
        else -> NasPublicationResult.Conflict
    }
}
