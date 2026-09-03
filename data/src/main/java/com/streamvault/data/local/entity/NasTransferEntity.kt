package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasTransferStatus

/**
 * The durable transfer ledger for the later foreground service. It deliberately never contains
 * a password and keeps sourceUri opaque so SAF-backed downloads remain supported.
 */
@Entity(
    tableName = "nas_transfers",
    indices = [
        Index(value = ["status"]),
        Index(value = ["download_id"]),
        Index(value = ["updated_at"])
    ]
)
data class NasTransferEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "download_id") val downloadId: String?,
    @ColumnInfo(name = "content_name") val contentName: String,
    @ColumnInfo(name = "local_file_name") val localFileName: String,
    @ColumnInfo(name = "local_source_uri") val localSourceUri: String,
    @ColumnInfo(name = "local_size_bytes") val localSizeBytes: Long,
    @ColumnInfo(name = "remote_directory") val remoteDirectory: String,
    @ColumnInfo(name = "remote_final_name") val remoteFinalName: String,
    @ColumnInfo(name = "remote_temporary_name") val remoteTemporaryName: String,
    @ColumnInfo(name = "status") val status: NasTransferStatus,
    @ColumnInfo(name = "bytes_transferred") val bytesTransferred: Long = 0L,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
) {
    fun toDomain(): NasTransfer = NasTransfer(
        id = id,
        downloadId = downloadId,
        contentName = contentName,
        localFileName = localFileName,
        localSourceUri = localSourceUri,
        localSizeBytes = localSizeBytes,
        remoteDirectory = remoteDirectory,
        remoteFinalName = remoteFinalName,
        remoteTemporaryName = remoteTemporaryName,
        status = status,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        lastError = lastError,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )

    companion object {
        fun fromDomain(transfer: NasTransfer): NasTransferEntity = NasTransferEntity(
            id = transfer.id,
            downloadId = transfer.downloadId,
            contentName = transfer.contentName,
            localFileName = transfer.localFileName,
            localSourceUri = transfer.localSourceUri,
            localSizeBytes = transfer.localSizeBytes,
            remoteDirectory = transfer.remoteDirectory,
            remoteFinalName = transfer.remoteFinalName,
            remoteTemporaryName = transfer.remoteTemporaryName,
            status = transfer.status,
            bytesTransferred = transfer.bytesTransferred,
            totalBytes = transfer.totalBytes,
            lastError = transfer.lastError,
            createdAt = transfer.createdAt,
            updatedAt = transfer.updatedAt,
            completedAt = transfer.completedAt
        )
    }
}
