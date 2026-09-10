package com.streamvault.data.nas

import com.streamvault.domain.manager.NasFilePublisher
import com.streamvault.domain.model.NasPublicationResult
import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasTransferStatus
import com.streamvault.domain.repository.NasCredentialStore
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasTransferRepository
import com.streamvault.domain.repository.NasTransferSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Executes one persisted pending transfer. Never retries publication or deletes the local source. */
class NasTransferExecutor @Inject constructor(
    private val repository: NasTransferRepository,
    private val settingsRepository: NasTransferSettingsRepository,
    private val credentialStore: NasCredentialStore,
    private val sourceFactory: AndroidNasTransferSourceFactory,
    private val publisher: NasFilePublisher
) {
    /** True means a final outcome was persisted; false means the row/update was refused. */
    suspend fun execute(transferId: String): Boolean {
        val pending = repository.getById(transferId) ?: return false
        if (pending.status != NasTransferStatus.PENDING) return false
        var password: CharArray? = null
        var active: NasTransfer? = null
        try {
            val settings = try {
                settingsRepository.observeSettings().first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failPending(pending, PreparationError.CONFIGURATION_UNAVAILABLE)
            }
            val trust = settings.trustedHostKey
            if (!settings.enabled || settings.validationErrors(requirePassword = false).isNotEmpty() ||
                trust == null || trust.host != settings.host || trust.port != settings.port
            ) return failPending(pending, PreparationError.INVALID_CONFIGURATION)

            password = try {
                credentialStore.readPassword()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failPending(pending, PreparationError.PASSWORD_UNAVAILABLE)
            }
            val secret = password
            if (secret == null || secret.isEmpty()) return failPending(pending, PreparationError.PASSWORD_UNAVAILABLE)
            val source = try {
                sourceFactory.fromPersistedDownload(pending.localSourceUri, pending.localFileName, pending.localSizeBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failPending(pending, PreparationError.SOURCE_UNAVAILABLE)
            } ?: return failPending(pending, PreparationError.SOURCE_UNAVAILABLE)
            if (source.sizeBytes != pending.localSizeBytes) return failPending(pending, PreparationError.SOURCE_SIZE_MISMATCH)

            currentCoroutineContext().ensureActive()
            // Record acceptance before returning to the cancellable context, including cancellation
            // racing with the Room commit. This prevents an accepted claim being left untracked.
            withContext(NonCancellable) {
                val next = pending.copy(status = NasTransferStatus.IN_PROGRESS, updatedAt = timestamp(pending))
                if (repository.update(next)) active = next
            }
            val running = active ?: return false
            currentCoroutineContext().ensureActive()
            val finalPath = pending.remoteDirectory.trimEnd('/') + "/" + pending.remoteFinalName
            var lastProgressPersistedAtNanos = System.nanoTime()
            var lastProgressUpdatedAt = running.updatedAt
            val progressPersistIntervalNanos = 1_000_000_000L

            val result = publisher.publish(
                NasSftpConnection(settings, secret, trust),
                source,
                finalPath
            ) { bytesTransferred ->
                val nowNanos = System.nanoTime()
                val completedUpload = bytesTransferred == running.totalBytes
                if (completedUpload ||
                    nowNanos - lastProgressPersistedAtNanos >= progressPersistIntervalNanos
                ) {
                    val progressUpdatedAt = maxOf(System.currentTimeMillis(), lastProgressUpdatedAt)
                    val persisted = try {
                        repository.updateProgress(
                            running.id,
                            bytesTransferred,
                            progressUpdatedAt
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }

                    if (persisted) {
                        lastProgressPersistedAtNanos = nowNanos
                        lastProgressUpdatedAt = progressUpdatedAt
                    }
                }
            }
            val status = when (result) {
                NasPublicationResult.Success -> NasTransferStatus.TRANSFERRED
                NasPublicationResult.AlreadyPresent -> NasTransferStatus.ALREADY_PRESENT
                NasPublicationResult.Conflict -> NasTransferStatus.CONFLICT
                is NasPublicationResult.Failure -> NasTransferStatus.FAILED
            }
            val now = timestamp(running)
            val finished = running.copy(
                status = status,
                bytesTransferred = if (status == NasTransferStatus.TRANSFERRED) running.totalBytes else 0L,
                lastError = if (result is NasPublicationResult.Failure) {
                    result.reason.name + (result.sftpError?.let { ":${it.name}" } ?: "")
                } else null,
                updatedAt = now,
                completedAt = if (status == NasTransferStatus.FAILED) null else now
            )
            return repository.update(finished)
        } catch (cancelled: CancellationException) {
            active?.let { running ->
                try {
                    withContext(NonCancellable) {
                        repository.update(running.copy(
                            status = NasTransferStatus.INTERRUPTED,
                            bytesTransferred = 0L,
                            lastError = null,
                            completedAt = null,
                            updatedAt = timestamp(running)
                        ))
                    }
                } catch (_: Exception) {
                    // Preserve the original cancellation without attaching storage/connection data.
                    cancelled.addSuppressed(IllegalStateException("NAS interruption could not be persisted"))
                }
            }
            throw cancelled
        } finally {
            password?.fill('\u0000')
        }
    }

    private suspend fun failPending(row: NasTransfer, error: PreparationError): Boolean = repository.update(
        row.copy(status = NasTransferStatus.FAILED, bytesTransferred = 0L, lastError = error.name,
            completedAt = null, updatedAt = timestamp(row))
    )

    private fun timestamp(row: NasTransfer): Long = maxOf(System.currentTimeMillis(), row.updatedAt)

    private enum class PreparationError {
        CONFIGURATION_UNAVAILABLE, INVALID_CONFIGURATION, PASSWORD_UNAVAILABLE, SOURCE_UNAVAILABLE, SOURCE_SIZE_MISMATCH
    }
}
