package com.streamvault.domain.model

/** Authentication modes deliberately supported by the NAS transfer feature. */
enum class NasAuthenticationMethod {
    PASSWORD
}

/** What may happen to the local source after a verified, real transfer. */
enum class NasLocalFilePolicy {
    KEEP_LOCAL,
    DELETE_AFTER_VALIDATED_TRANSFER
}

/** Durable state for a NAS transfer. `ALREADY_PRESENT` is not a successful upload. */
enum class NasTransferStatus {
    PENDING,
    IN_PROGRESS,
    INTERRUPTED,
    TRANSFERRED,
    FAILED,
    CONFLICT,
    ALREADY_PRESENT
}

/**
 * A server key explicitly approved by the user. The host and port are included so a fingerprint
 * cannot silently be reused for another destination.
 */
data class NasHostKeyTrust(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(algorithm.isNotBlank()) { "algorithm must not be blank" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    }
}

/** Non-sensitive, persistable NAS configuration. The SSH secret is intentionally absent. */
data class NasTransferSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val authenticationMethod: NasAuthenticationMethod = NasAuthenticationMethod.PASSWORD,
    val remoteDirectory: String = "",
    val localFilePolicy: NasLocalFilePolicy = NasLocalFilePolicy.KEEP_LOCAL,
    val trustedHostKey: NasHostKeyTrust? = null
) {
    companion object {
        const val DEFAULT_PORT = 22
    }

    fun validationErrors(requirePassword: Boolean): Map<NasTransferConfigurationField, NasTransferConfigurationError> {
        val errors = linkedMapOf<NasTransferConfigurationField, NasTransferConfigurationError>()
        if (host.isBlank()) errors[NasTransferConfigurationField.HOST] = NasTransferConfigurationError.REQUIRED
        if (port !in 1..65_535) errors[NasTransferConfigurationField.PORT] = NasTransferConfigurationError.INVALID_PORT
        if (username.isBlank()) errors[NasTransferConfigurationField.USERNAME] = NasTransferConfigurationError.REQUIRED
        if (remoteDirectory.isBlank()) {
            errors[NasTransferConfigurationField.REMOTE_DIRECTORY] = NasTransferConfigurationError.REQUIRED
        }
        if (requirePassword && authenticationMethod == NasAuthenticationMethod.PASSWORD) {
            errors[NasTransferConfigurationField.PASSWORD] = NasTransferConfigurationError.REQUIRED
        }
        return errors
    }

    fun normalized(): NasTransferSettings = copy(
        host = host.trim(),
        username = username.trim(),
        remoteDirectory = remoteDirectory.trim()
    )
}

enum class NasTransferConfigurationField {
    HOST,
    PORT,
    USERNAME,
    REMOTE_DIRECTORY,
    PASSWORD
}

enum class NasTransferConfigurationError {
    REQUIRED,
    INVALID_PORT
}

/** A persisted transfer record. The source URI is opaque and must never be coerced into a file path. */
data class NasTransfer(
    val id: String,
    val downloadId: String?,
    val contentName: String,
    val localFileName: String,
    val localSourceUri: String,
    val localSizeBytes: Long,
    val remoteDirectory: String,
    val remoteFinalName: String,
    val remoteTemporaryName: String,
    val status: NasTransferStatus,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(localSourceUri.isNotBlank()) { "localSourceUri must not be blank" }
        require(localSizeBytes >= 0L) { "localSizeBytes must not be negative" }
        require(bytesTransferred >= 0L) { "bytesTransferred must not be negative" }
        require(totalBytes >= 0L) { "totalBytes must not be negative" }
    }
}

/** The local source deletion decision is intentionally separate from transfer execution. */
fun NasTransfer.allowsLocalSourceDeletion(policy: NasLocalFilePolicy): Boolean =
    status == NasTransferStatus.TRANSFERRED && policy == NasLocalFilePolicy.DELETE_AFTER_VALIDATED_TRANSFER

/** Only transitions needed by the future single-active-transfer queue are permitted. */
fun NasTransferStatus.canTransitionTo(next: NasTransferStatus): Boolean = when (this) {
    NasTransferStatus.PENDING -> next == NasTransferStatus.IN_PROGRESS || next == NasTransferStatus.FAILED || next == NasTransferStatus.CONFLICT || next == NasTransferStatus.ALREADY_PRESENT
    NasTransferStatus.IN_PROGRESS -> next in setOf(NasTransferStatus.INTERRUPTED, NasTransferStatus.TRANSFERRED, NasTransferStatus.FAILED, NasTransferStatus.CONFLICT, NasTransferStatus.ALREADY_PRESENT)
    NasTransferStatus.INTERRUPTED -> next in setOf(NasTransferStatus.PENDING, NasTransferStatus.IN_PROGRESS, NasTransferStatus.FAILED)
    NasTransferStatus.FAILED -> next == NasTransferStatus.PENDING
    NasTransferStatus.CONFLICT, NasTransferStatus.ALREADY_PRESENT, NasTransferStatus.TRANSFERRED -> false
}
