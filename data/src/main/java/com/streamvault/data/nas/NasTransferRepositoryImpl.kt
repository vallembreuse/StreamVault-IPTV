package com.streamvault.data.nas

import androidx.room.withTransaction
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.NasTransferEntity
import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasTransferStatus
import com.streamvault.domain.model.canTransitionTo
import com.streamvault.domain.repository.NasTransferRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class NasTransferRepositoryImpl @Inject constructor(
    private val database: StreamVaultDatabase
) : NasTransferRepository {
    private val dao = database.nasTransferDao()

    override fun observeRecoverableQueue(): Flow<List<NasTransfer>> = dao.observeAll().map { rows ->
        rows.filter {
            it.status == NasTransferStatus.PENDING || it.status == NasTransferStatus.IN_PROGRESS ||
                it.status == NasTransferStatus.INTERRUPTED
        }.map { it.toDomain() }
    }

    override suspend fun getById(id: String): NasTransfer? = dao.getById(id)?.toDomain()

    override suspend fun insert(transfer: NasTransfer) {
        require(
            transfer.status == NasTransferStatus.PENDING && transfer.bytesTransferred == 0L &&
                transfer.totalBytes == transfer.localSizeBytes && transfer.lastError == null &&
                transfer.completedAt == null && transfer.createdAt == transfer.updatedAt &&
                transfer.remoteTemporaryName == transfer.remoteFinalName + ".part" &&
                transfer.localSizeBytes >= 0L && transfer.totalBytes >= 0L &&
                listOf(
                    transfer.id, transfer.contentName, transfer.localFileName, transfer.localSourceUri,
                    transfer.remoteDirectory, transfer.remoteFinalName, transfer.remoteTemporaryName
                ).all { it.isNotBlank() }
        ) { "Invalid initial NAS transfer" }
        dao.insert(NasTransferEntity.fromDomain(transfer))
    }

    override suspend fun updateProgress(
        id: String,
        bytesTransferred: Long,
        updatedAt: Long
    ): Boolean = database.withTransaction {
        val current = dao.getById(id)?.toDomain() ?: return@withTransaction false
        if (current.status != NasTransferStatus.IN_PROGRESS) return@withTransaction false
        if (bytesTransferred < current.bytesTransferred || bytesTransferred > current.totalBytes) {
            return@withTransaction false
        }
        if (updatedAt < current.updatedAt) return@withTransaction false

        dao.update(
            NasTransferEntity.fromDomain(
                current.copy(
                    bytesTransferred = bytesTransferred,
                    updatedAt = updatedAt
                )
            )
        )
        true
    }

    override suspend fun update(transfer: NasTransfer): Boolean = database.withTransaction {
        val current = dao.getById(transfer.id)?.toDomain() ?: return@withTransaction false
        if (current.status == transfer.status || !current.status.canTransitionTo(transfer.status)) {
            return@withTransaction false
        }
        // Only execution state may change; compare all remaining fields against the persisted row.
        if (current.copy(
                status = transfer.status,
                bytesTransferred = transfer.bytesTransferred,
                lastError = transfer.lastError,
                updatedAt = transfer.updatedAt,
                completedAt = transfer.completedAt
            ) != transfer
        ) return@withTransaction false
        if (transfer.updatedAt < current.updatedAt || !transfer.hasValidState()) return@withTransaction false
        dao.update(NasTransferEntity.fromDomain(transfer))
        true
    }

    private fun NasTransfer.hasValidState(): Boolean = when (status) {
        NasTransferStatus.TRANSFERRED ->
            bytesTransferred == totalBytes && lastError == null && completedAt != null
        NasTransferStatus.ALREADY_PRESENT, NasTransferStatus.CONFLICT ->
            bytesTransferred == 0L && lastError == null && completedAt != null
        NasTransferStatus.PENDING, NasTransferStatus.IN_PROGRESS ->
            bytesTransferred == 0L && lastError == null && completedAt == null
        NasTransferStatus.INTERRUPTED, NasTransferStatus.FAILED ->
            bytesTransferred == 0L && completedAt == null
    }
}
