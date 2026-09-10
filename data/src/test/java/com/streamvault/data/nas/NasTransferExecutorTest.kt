package com.streamvault.data.nas

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.NasFilePublisher
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.*
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class NasTransferExecutorTest {
    @Test fun `success persists transferred and invokes publisher exactly once`() = runTest {
        val f = Fixture()
        assertThat(f.executor.execute("id")).isTrue()
        f.assertOutcome(NasTransferStatus.TRANSFERRED, null)
        assertThat(f.repo.row!!.bytesTransferred).isEqualTo(42L)
        val connection = argumentCaptor<NasSftpConnection>()
        verify(f.publisher, times(1)).publish(connection.capture(), same(f.source), eq("/films/movie.mkv"), any())
        assertThat(connection.firstValue.password).isSameInstanceAs(f.password)
        verify(f.factory).fromPersistedDownload("content://movie", "movie.mkv", 42L)
        f.assertZeroed()
    }

    @Test fun `already present persists zero bytes and completion`() = runTest {
        val f = Fixture(NasPublicationResult.AlreadyPresent)
        f.executor.execute("id")
        f.assertOutcome(NasTransferStatus.ALREADY_PRESENT, null)
        assertThat(f.repo.row!!.bytesTransferred).isEqualTo(0L)
        f.assertZeroed()
    }

    @Test fun `conflict persists zero bytes and completion`() = runTest {
        val f = Fixture(NasPublicationResult.Conflict)
        f.executor.execute("id")
        f.assertOutcome(NasTransferStatus.CONFLICT, null)
        assertThat(f.repo.row!!.bytesTransferred).isEqualTo(0L)
        f.assertZeroed()
    }

    @Test fun `publisher failures store only enum names`() = runTest {
        for (error in listOf(null, NasSftpError.TIMEOUT)) {
            val f = Fixture(NasPublicationResult.Failure(NasPublicationFailureReason.SFTP_FAILURE, error))
            f.executor.execute("id")
            f.assertOutcome(NasTransferStatus.FAILED, "SFTP_FAILURE" + (error?.let { ":${it.name}" } ?: ""))
            assertThat(f.repo.row!!.bytesTransferred).isEqualTo(0L)
            f.assertZeroed()
        }
    }

    @Test fun `missing source fails before claiming and clears password`() = runTest {
        val f = Fixture()
        whenever(f.factory.fromPersistedDownload(any(), any(), any())).thenReturn(null)
        f.executor.execute("id")
        f.assertPreparationFailure("SOURCE_UNAVAILABLE")
        f.assertZeroed()
    }

    @Test fun `missing or empty password fails without source access`() = runTest {
        for (secret in listOf(null, charArrayOf())) {
            val f = Fixture()
            whenever(f.credentials.readPassword()).thenReturn(secret)
            f.executor.execute("id")
            f.assertPreparationFailure("PASSWORD_UNAVAILABLE")
            verifyNoInteractions(f.factory)
        }
    }

    @Test fun `invalid disabled missing trust and mismatched trust configurations fail early`() = runTest {
        val base = Fixture().settings
        for (settings in listOf(base.copy(enabled = false), base.copy(host = ""), base.copy(port = 0),
            base.copy(username = ""), base.copy(remoteDirectory = ""), base.copy(trustedHostKey = null),
            base.copy(trustedHostKey = base.trustedHostKey!!.copy(host = "other")),
            base.copy(trustedHostKey = base.trustedHostKey!!.copy(port = 23)))) {
            val f = Fixture()
            whenever(f.settingsRepo.observeSettings()).thenReturn(flowOf(settings))
            f.executor.execute("id")
            f.assertPreparationFailure("INVALID_CONFIGURATION")
            verifyNoInteractions(f.credentials, f.factory)
        }
    }

    @Test fun `missing transfer refuses without dependencies`() = runTest {
        val f = Fixture()
        f.repo.row = null
        assertThat(f.executor.execute("id")).isFalse()
        assertThat(f.repo.updates).isEmpty()
        verifyNoInteractions(f.settingsRepo, f.credentials, f.factory, f.publisher)
    }

    @Test fun `non pending states refuse without dependencies`() = runTest {
        for (status in NasTransferStatus.entries.filter { it != NasTransferStatus.PENDING }) {
            val f = Fixture()
            f.repo.row = f.repo.row!!.copy(status = status)
            assertThat(f.executor.execute("id")).isFalse()
            assertThat(f.repo.updates).isEmpty()
            verifyNoInteractions(f.settingsRepo, f.credentials, f.factory, f.publisher)
        }
    }

    @Test fun `publisher cancellation persists interrupted and propagates original`() = runTest {
        val f = Fixture()
        val cancellation = CancellationException("test cancellation")
        doThrow(cancellation).whenever(f.publisher).publish(any(), any(), any(), any())
        try {
            f.executor.execute("id")
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) { assertThat(actual).isSameInstanceAs(cancellation) }
        assertThat(f.repo.updates.map { it.status }).containsExactly(NasTransferStatus.IN_PROGRESS, NasTransferStatus.INTERRUPTED).inOrder()
        assertThat(f.repo.row!!.completedAt).isNull()
        assertThat(f.repo.row!!.bytesTransferred).isEqualTo(0L)
        f.assertZeroed()
        verify(f.publisher, times(1)).publish(any(), any(), any(), any())
    }

    @Test fun `real job cancellation during accepted claim persists interrupted without publishing`() = runTest {
        val f = Fixture()
        val job = launch {
            val executionContext = currentCoroutineContext()
            f.repo.afterClaim = { executionContext.cancel() }
            f.executor.execute("id")
        }
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(f.repo.row!!.status).isEqualTo(NasTransferStatus.INTERRUPTED)
        verifyNoInteractions(f.publisher)
        f.assertZeroed()
    }

    @Test fun `refused in progress update prevents publishing and clears password`() = runTest {
        val f = Fixture()
        f.repo.reject = NasTransferStatus.IN_PROGRESS
        assertThat(f.executor.execute("id")).isFalse()
        verifyNoInteractions(f.publisher)
        assertThat(f.repo.row!!.status).isEqualTo(NasTransferStatus.PENDING)
        f.assertZeroed()
    }

    @Test fun `refused terminal update is reported without republishing`() = runTest {
        val f = Fixture()
        f.repo.reject = NasTransferStatus.TRANSFERRED
        assertThat(f.executor.execute("id")).isFalse()
        verify(f.publisher, times(1)).publish(any(), any(), any(), any())
        f.assertZeroed()
    }

    @Test fun `preparation exceptions are sanitized and source size changes refuse publication`() = runTest {
        val f = Fixture()
        whenever(f.factory.fromPersistedDownload(any(), any(), any())).thenThrow(SecurityException("untrusted details"))
        f.executor.execute("id")
        f.assertPreparationFailure("SOURCE_UNAVAILABLE")
        f.assertZeroed()
        val changed = Fixture()
        whenever(changed.factory.fromPersistedDownload(any(), any(), any())).thenReturn(Source(43L))
        changed.executor.execute("id")
        changed.assertPreparationFailure("SOURCE_SIZE_MISMATCH")
        changed.assertZeroed()
    }

    @Test fun `credential exception becomes business failure`() = runTest {
        val f = Fixture()
        whenever(f.credentials.readPassword()).thenThrow(IllegalStateException("sensitive details"))
        f.executor.execute("id")
        f.assertPreparationFailure("PASSWORD_UNAVAILABLE")
    }

    @Test fun `cancellation before claim leaves pending and clears acquired password`() = runTest {
        val f = Fixture()
        whenever(f.factory.fromPersistedDownload(any(), any(), any())).thenThrow(CancellationException())
        try { f.executor.execute("id"); throw AssertionError("Expected cancellation") }
        catch (_: CancellationException) { }
        assertThat(f.repo.updates).isEmpty()
        verifyNoInteractions(f.publisher)
        f.assertZeroed()
    }

    private class Fixture(result: NasPublicationResult = NasPublicationResult.Success) {
        val repo = Ledger()
        val settings = NasTransferSettings(enabled = true, host = "nas", username = "user", remoteDirectory = "/films",
            trustedHostKey = NasHostKeyTrust("nas", 22, "ssh-ed25519", "test"))
        val settingsRepo = mock<NasTransferSettingsRepository> { on { observeSettings() } doReturn flowOf(settings) }
        val password = charArrayOf('t', 'e', 's', 't')
        val credentials = mock<NasCredentialStore> { onBlocking { readPassword() } doReturn password }
        val source = Source()
        val factory = mock<AndroidNasTransferSourceFactory> {
            on { fromPersistedDownload(any(), any(), any()) } doReturn source
        }
        val publisher = mock<NasFilePublisher> {
            onBlocking { publish(any(), any(), any(), any()) } doAnswer {
                assertThat(repo.row!!.status).isEqualTo(NasTransferStatus.IN_PROGRESS)
                result
            }
        }
        val executor = NasTransferExecutor(repo, settingsRepo, credentials, factory, publisher)
        fun assertZeroed() { assertThat(password.all { it == '\u0000' }).isTrue() }
        fun assertOutcome(status: NasTransferStatus, error: String?) {
            assertThat(repo.updates.map { it.status }).containsExactly(NasTransferStatus.IN_PROGRESS, status).inOrder()
            assertThat(repo.row!!.status).isEqualTo(status)
            assertThat(repo.row!!.lastError).isEqualTo(error)
            if (status == NasTransferStatus.FAILED) assertThat(repo.row!!.completedAt).isNull()
            else assertThat(repo.row!!.completedAt).isNotNull()
        }
        fun assertPreparationFailure(error: String) {
            assertThat(repo.updates.map { it.status }).containsExactly(NasTransferStatus.FAILED)
            assertThat(repo.row!!.lastError).isEqualTo(error)
            assertThat(repo.row!!.completedAt).isNull()
            verifyNoInteractions(publisher)
        }
    }

    private class Ledger : NasTransferRepository {
        var row: NasTransfer? = NasTransfer("id", null, "Movie", "movie.mkv", "content://movie", 42,
            "/films/", "movie.mkv", "movie.mkv.part", NasTransferStatus.PENDING, 0, 42, createdAt = 1, updatedAt = 1)
        val updates = mutableListOf<NasTransfer>()
        var reject: NasTransferStatus? = null
        var afterClaim: (() -> Unit)? = null
        override fun observeRecoverableQueue(): Flow<List<NasTransfer>> = error("Unexpected queue access")
        override suspend fun getById(id: String): NasTransfer? = row?.takeIf { it.id == id }
        override suspend fun insert(transfer: NasTransfer) { error("Unexpected insertion") }

        override suspend fun updateProgress(
            id: String,
            bytesTransferred: Long,
            updatedAt: Long
        ): Boolean {
            val current = row?.takeIf { it.id == id } ?: return false
            if (current.status != NasTransferStatus.IN_PROGRESS) return false
            if (bytesTransferred < current.bytesTransferred || bytesTransferred > current.totalBytes) {
                return false
            }
            if (updatedAt < current.updatedAt) return false

            row = current.copy(
                bytesTransferred = bytesTransferred,
                updatedAt = updatedAt
            )
            return true
        }

        override suspend fun update(transfer: NasTransfer): Boolean {
            if (transfer.status == reject) return false
            val current = row ?: return false
            assertThat(current.status.canTransitionTo(transfer.status)).isTrue()
            assertThat(transfer.updatedAt).isAtLeast(current.updatedAt)
            row = transfer
            updates += transfer
            if (transfer.status == NasTransferStatus.IN_PROGRESS) afterClaim?.invoke()
            return true
        }
    }

    private class Source(override val sizeBytes: Long = 42) : NasTransferSource {
        override val displayName = "movie.mkv"
        override val uriString = "content://movie"
        override suspend fun openInputStream(): InputStream = error("Executor must not open source")
        override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? = error("No resume")
        override suspend fun deleteAfterValidatedTransfer(): Boolean = error("No local deletion")
    }
}
