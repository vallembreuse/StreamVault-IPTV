package com.streamvault.domain.repository

import java.io.InputStream

/**
 * A locally downloaded file exposed without assuming it has a filesystem path. The future engine
 * streams from it and may request a seekable stream for an SFTP `.part` resume.
 */
interface NasTransferSource {
    val displayName: String
    val sizeBytes: Long
    val uriString: String

    suspend fun openInputStream(): InputStream

    /** Returns null when the platform/provider cannot safely seek to [offsetBytes]. */
    suspend fun openInputStreamAt(offsetBytes: Long): InputStream?

    /** Only a post-validation policy decision in the future transfer engine may call this. */
    suspend fun deleteAfterValidatedTransfer(): Boolean
}
