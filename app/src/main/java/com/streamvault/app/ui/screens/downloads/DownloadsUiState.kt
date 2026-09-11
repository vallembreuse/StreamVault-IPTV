package com.streamvault.app.ui.screens.downloads

import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasTransferStatus

/**
 * UI state for the Downloads screen.
 */
data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val isLoading: Boolean = true,
    val nasTransferInProgress: Boolean = false,
    val nasTransfersByDownloadId: Map<String, NasTransfer> = emptyMap(),
    val isNasTransfersLoading: Boolean = true,
    val storageConfig: DownloadStorageConfig = DownloadStorageConfig(),
    val userMessage: String? = null,
    val deleteConfirmItem: DownloadItem? = null
) {
    val hasActiveNasTransfer: Boolean
        get() = nasTransfersByDownloadId.values.any {
            it.status == NasTransferStatus.PENDING || it.status == NasTransferStatus.IN_PROGRESS
        }

    val isNasTransferActionEnabled: Boolean
        get() = !isNasTransfersLoading && !nasTransferInProgress && !hasActiveNasTransfer
}

internal fun DownloadItem.canTransferToNas(transfer: NasTransfer? = null): Boolean =
    status == com.streamvault.domain.model.DownloadStatus.COMPLETED && when (transfer?.status) {
        NasTransferStatus.PENDING, NasTransferStatus.IN_PROGRESS,
        NasTransferStatus.TRANSFERRED, NasTransferStatus.ALREADY_PRESENT -> false
        else -> true
    }

/** Unknown sizes use an indeterminate indicator; persisted counters may be stale or out of bounds. */
internal fun NasTransfer.progressFraction(): Float? =
    if (totalBytes > 0L) (bytesTransferred.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    else null
