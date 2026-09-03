package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NasTransferModelsTest {
    @Test
    fun `configuration validation reports each required field and accepts valid settings`() {
        val empty = NasTransferSettings(port = 0).validationErrors(requirePassword = true)

        assertThat(empty).containsExactly(
            NasTransferConfigurationField.HOST, NasTransferConfigurationError.REQUIRED,
            NasTransferConfigurationField.PORT, NasTransferConfigurationError.INVALID_PORT,
            NasTransferConfigurationField.USERNAME, NasTransferConfigurationError.REQUIRED,
            NasTransferConfigurationField.REMOTE_DIRECTORY, NasTransferConfigurationError.REQUIRED,
            NasTransferConfigurationField.PASSWORD, NasTransferConfigurationError.REQUIRED
        )

        val valid = NasTransferSettings(
            host = "nas.example",
            port = 22,
            username = "streamvault",
            remoteDirectory = "/films"
        )
        assertThat(valid.validationErrors(requirePassword = false)).isEmpty()
    }

    @Test
    fun `terminal states remain distinct and interrupted transfers can be resumed`() {
        assertThat(NasTransferStatus.ALREADY_PRESENT).isNotEqualTo(NasTransferStatus.TRANSFERRED)
        assertThat(NasTransferStatus.CONFLICT).isNotEqualTo(NasTransferStatus.FAILED)
        assertThat(NasTransferStatus.INTERRUPTED.canTransitionTo(NasTransferStatus.PENDING)).isTrue()
        assertThat(NasTransferStatus.INTERRUPTED.canTransitionTo(NasTransferStatus.IN_PROGRESS)).isTrue()
        assertThat(NasTransferStatus.TRANSFERRED.canTransitionTo(NasTransferStatus.PENDING)).isFalse()
    }

    @Test
    fun `only a real validated transfer may permit local source deletion`() {
        val statuses = NasTransferStatus.entries.associateWith { status ->
            transfer(status).allowsLocalSourceDeletion(NasLocalFilePolicy.DELETE_AFTER_VALIDATED_TRANSFER)
        }

        assertThat(statuses[NasTransferStatus.TRANSFERRED]).isTrue()
        assertThat(statuses[NasTransferStatus.ALREADY_PRESENT]).isFalse()
        assertThat(statuses[NasTransferStatus.CONFLICT]).isFalse()
        assertThat(statuses[NasTransferStatus.FAILED]).isFalse()
        assertThat(statuses[NasTransferStatus.INTERRUPTED]).isFalse()
        assertThat(statuses[NasTransferStatus.IN_PROGRESS]).isFalse()
        assertThat(statuses[NasTransferStatus.PENDING]).isFalse()
        assertThat(
            transfer(NasTransferStatus.TRANSFERRED)
                .allowsLocalSourceDeletion(NasLocalFilePolicy.KEEP_LOCAL)
        ).isFalse()
    }

    private fun transfer(status: NasTransferStatus) = NasTransfer(
        id = "nas-1",
        downloadId = "download-1",
        contentName = "Movie",
        localFileName = "movie.mkv",
        localSourceUri = "content://downloads/movie.mkv",
        localSizeBytes = 20_000_000_000L,
        remoteDirectory = "/films",
        remoteFinalName = "movie.mkv",
        remoteTemporaryName = "movie.mkv.part",
        status = status,
        bytesTransferred = 0L,
        totalBytes = 20_000_000_000L,
        createdAt = 1L,
        updatedAt = 1L
    )
}
