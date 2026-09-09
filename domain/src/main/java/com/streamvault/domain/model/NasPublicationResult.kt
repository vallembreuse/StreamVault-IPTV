package com.streamvault.domain.model

import com.streamvault.domain.repository.NasSftpError

sealed interface NasPublicationResult {
    data object Success : NasPublicationResult
    data object AlreadyPresent : NasPublicationResult
    data object Conflict : NasPublicationResult

    data class Failure(
        val reason: NasPublicationFailureReason,
        val sftpError: NasSftpError? = null
    ) : NasPublicationResult
}

enum class NasPublicationFailureReason {
    INVALID_INPUT,
    PART_ALREADY_EXISTS,
    SFTP_FAILURE,
    SIZE_MISMATCH,
    FINAL_MISSING,
    PART_STILL_PRESENT
}
