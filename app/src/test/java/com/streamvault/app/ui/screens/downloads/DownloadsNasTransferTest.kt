package com.streamvault.app.ui.screens.downloads

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.MainDispatcherRule
import com.streamvault.data.nas.NasTransferExecutor
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.*

class DownloadsNasTransferTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `NAS action is available only for completed downloads`() {
        for (status in DownloadStatus.entries) {
            assertThat(download(status).canTransferToNas()).isEqualTo(status == DownloadStatus.COMPLETED)
        }
    }

    @Test fun `explicit action inserts correct ledger then executes its id without deleting local download`() = runBlocking<Unit> {
        val f = Fixture(download())
        verify(f.ledger).observeAll()
        verifyNoInteractions(f.executor)
        f.startAndAwait()
        val row = argumentCaptor<NasTransfer>()
        val order = inOrder(f.ledger, f.executor)
        order.verify(f.ledger).insert(row.capture())
        order.verify(f.executor).execute(row.firstValue.id)
        val t = row.firstValue
        assertThat(UUID.fromString(t.id).toString()).isEqualTo(t.id)
        assertThat(t).isEqualTo(NasTransfer(
            id = t.id, downloadId = "download", contentName = "Movie", localFileName = "movie.mkv",
            localSourceUri = "content://opaque/document/123", localSizeBytes = 4992,
            remoteDirectory = "/volume1/Media/Films", remoteFinalName = "movie.mkv", remoteTemporaryName = "movie.mkv.part",
            status = NasTransferStatus.PENDING, bytesTransferred = 0, totalBytes = 4992,
            createdAt = t.createdAt, updatedAt = t.createdAt
        ))
        assertThat(t.createdAt).isGreaterThan(0L)
        verify(f.executor, times(1)).execute(t.id)
        verify(f.manager, never()).deleteDownload(any())
        verify(f.manager).observeAllDownloads()
        verify(f.manager).observeStorageState()
        verifyNoMoreInteractions(f.manager)
    }

    @Test fun `non completed download never inserts or executes`() = runBlocking<Unit> {
        for (status in DownloadStatus.entries.filter { it != DownloadStatus.COMPLETED }) {
            val f = Fixture(download(status))
            f.vm.transferToNas(f.item)
            verify(f.ledger).observeAll()
            verify(f.ledger, never()).insert(any())
            verifyNoInteractions(f.executor, f.settings)
        }
    }

    @Test fun `missing URI or file name refuses without executor`() = runBlocking<Unit> {
        for (item in listOf(download().copy(outputUri = null), download().copy(outputDisplayPath = null))) {
            val f = Fixture(item)
            f.startAndAwait()
            verify(f.ledger).observeAll()
            verify(f.ledger, never()).insert(any())
            verifyNoInteractions(f.executor)
            verify(f.manager, never()).deleteDownload(any())
        }
    }

    @Test fun `insert failure never executes or replaces an existing transfer`() = runBlocking<Unit> {
        val f = Fixture(download())
        doThrow(IllegalStateException("insert refused")).whenever(f.ledger).insert(any())
        f.startAndAwait()
        verifyNoInteractions(f.executor)
        verify(f.ledger, never()).update(any())
        verify(f.manager, never()).deleteDownload(any())
    }

    @Test fun `second click while execution is active does not create another transfer`() = runBlocking<Unit> {
        val f = Fixture(download())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(f.executor.execute(any())).doSuspendableAnswer {
            entered.complete(Unit)
            release.await()
            true
        }
        f.vm.transferToNas(f.item)
        withTimeout(5_000) { entered.await() }
        try {
            assertThat(f.vm.uiState.value.nasTransferInProgress).isTrue()
            assertThat(f.vm.uiState.value.isNasTransferActionEnabled).isFalse()
            f.vm.transferToNas(f.item)
        } finally { release.complete(Unit) }
        f.awaitIdle()
        verify(f.ledger, times(1)).insert(any())
        verify(f.executor, times(1)).execute(any())
    }

    @Test fun `latest NAS transfer per download is exposed from persistent ledger`() = runBlocking<Unit> {
        val older = NasTransfer(
            id = "older",
            downloadId = "download",
            contentName = "Movie",
            localFileName = "movie.mkv",
            localSourceUri = "content://opaque/document/123",
            localSizeBytes = 4992,
            remoteDirectory = "/volume1/Media/Films",
            remoteFinalName = "movie.mkv",
            remoteTemporaryName = "movie.mkv.part",
            status = NasTransferStatus.IN_PROGRESS,
            bytesTransferred = 1200,
            totalBytes = 4992,
            createdAt = 100,
            updatedAt = 200
        )
        val newer = older.copy(
            id = "newer",
            status = NasTransferStatus.TRANSFERRED,
            bytesTransferred = 4992,
            createdAt = 300,
            updatedAt = 400,
            completedAt = 400
        )

        val f = Fixture(download(), listOf(newer, older))

        val selected = withTimeout(5_000) {
            f.vm.uiState.first {
                it.nasTransfersByDownloadId["download"]?.id == "newer"
            }.nasTransfersByDownloadId["download"]
        }

        assertThat(selected).isEqualTo(newer)
    }

    @Test fun `progress follows ledger emissions without a local manual transfer`() = runBlocking<Unit> {
        val pending = transfer(NasTransferStatus.PENDING).copy(bytesTransferred = 0)
        val rows = MutableStateFlow(listOf(pending))
        val f = Fixture(download(), transferFlow = rows)
        assertThat(f.vm.uiState.value.nasTransfersByDownloadId["download"]?.progressFraction()).isEqualTo(0f)
        val active = pending.copy(status = NasTransferStatus.IN_PROGRESS, bytesTransferred = 37, updatedAt = 200)
        rows.value = listOf(active)
        val state = withTimeout(5_000) {
            f.vm.uiState.first { it.nasTransfersByDownloadId["download"] == active }
        }
        assertThat(state.nasTransfersByDownloadId["download"]?.progressFraction()).isEqualTo(0.37f)
        assertThat(state.nasTransferInProgress).isFalse()
        assertThat(f.item.canTransferToNas(state.nasTransfersByDownloadId["download"])).isFalse()
        verifyNoInteractions(f.executor)
    }

    @Test fun `finished ledger state survives ViewModel recreation without offering another transfer`() = runBlocking<Unit> {
        for (status in listOf(NasTransferStatus.TRANSFERRED, NasTransferStatus.ALREADY_PRESENT)) {
            val rows = MutableStateFlow(listOf(transfer(NasTransferStatus.IN_PROGRESS)))
            val original = Fixture(download(), transferFlow = rows)
            val finished = transfer(status).copy(bytesTransferred = 100, completedAt = 200)
            rows.value = listOf(finished)
            withTimeout(5_000) {
                original.vm.uiState.first { it.nasTransfersByDownloadId["download"] == finished }
            }
            val recreated = Fixture(download(), transferFlow = rows)
            val state = recreated.vm.uiState.value
            assertThat(state.nasTransfersByDownloadId["download"]).isEqualTo(finished)
            assertThat(recreated.item.canTransferToNas(state.nasTransfersByDownloadId["download"])).isFalse()
            recreated.vm.transferToNas(recreated.item)
            verify(recreated.ledger, never()).insert(any())
            verifyNoInteractions(original.executor, recreated.executor)
        }
    }

    @Test fun `pending and active ledger rows block a new manual transfer`() = runBlocking<Unit> {
        for (status in listOf(NasTransferStatus.PENDING, NasTransferStatus.IN_PROGRESS)) {
            val f = Fixture(download(), listOf(transfer(status)))
            f.vm.transferToNas(f.item)
            verify(f.ledger, never()).insert(any())
            verifyNoInteractions(f.executor)
        }
    }

    @Test fun `persistent active transfer on download A blocks download B after ViewModel creation`() = runBlocking<Unit> {
        for (status in listOf(NasTransferStatus.PENDING, NasTransferStatus.IN_PROGRESS)) {
            val downloadA = download().copy(id = "A")
            val downloadB = download().copy(id = "B")
            val f = Fixture(
                downloadB,
                transfers = listOf(transfer(status).copy(downloadId = downloadA.id)),
                downloads = listOf(downloadA, downloadB)
            )
            val state = f.vm.uiState.value
            assertThat(state.nasTransferInProgress).isFalse()
            assertThat(state.hasActiveNasTransfer).isTrue()
            assertThat(state.isNasTransferActionEnabled).isFalse()
            assertThat(state.nasTransfersByDownloadId[downloadB.id]).isNull()

            f.vm.transferToNas(downloadB)
            f.awaitIdle()

            verify(f.ledger, never()).insert(any())
            verifyNoInteractions(f.executor)
        }
    }

    @Test fun `non active transfer on download A does not block download B globally`() = runBlocking<Unit> {
        for (status in listOf(
            NasTransferStatus.FAILED, NasTransferStatus.CONFLICT, NasTransferStatus.INTERRUPTED,
            NasTransferStatus.TRANSFERRED, NasTransferStatus.ALREADY_PRESENT
        )) {
            val downloadA = download().copy(id = "A")
            val downloadB = download().copy(id = "B")
            val f = Fixture(
                downloadB,
                transfers = listOf(transfer(status).copy(downloadId = downloadA.id)),
                downloads = listOf(downloadA, downloadB)
            )
            assertThat(f.vm.uiState.value.hasActiveNasTransfer).isFalse()
            assertThat(f.vm.uiState.value.isNasTransferActionEnabled).isTrue()

            f.startAndAwait()

            val inserted = argumentCaptor<NasTransfer>()
            verify(f.ledger, times(1)).insert(inserted.capture())
            assertThat(inserted.firstValue.downloadId).isEqualTo(downloadB.id)
            verify(f.executor, times(1)).execute(inserted.firstValue.id)
        }
    }

    @Test fun `failed conflict and interrupted transfers still allow manual transfer`() = runBlocking<Unit> {
        for (status in listOf(NasTransferStatus.FAILED, NasTransferStatus.CONFLICT, NasTransferStatus.INTERRUPTED)) {
            val row = transfer(status)
            val f = Fixture(download(), listOf(row))
            assertThat(f.item.canTransferToNas(row)).isTrue()
            f.startAndAwait()
            verify(f.ledger, times(1)).insert(any())
            verify(f.executor, times(1)).execute(any())
            verify(f.manager, never()).deleteDownload(any())
        }
    }

    @Test fun `ledger must load before a manual transfer can start`() = runBlocking<Unit> {
        val rows = kotlinx.coroutines.flow.MutableSharedFlow<List<NasTransfer>>()
        val f = Fixture(download(), transferFlow = rows)
        assertThat(f.vm.uiState.value.isNasTransfersLoading).isTrue()
        assertThat(f.vm.uiState.value.isNasTransferActionEnabled).isFalse()
        f.vm.transferToNas(f.item)
        verify(f.ledger, never()).insert(any())
        rows.emit(emptyList())
        withTimeout(5_000) { f.vm.uiState.first { !it.isNasTransfersLoading } }
        assertThat(f.vm.uiState.value.isNasTransferActionEnabled).isTrue()
        f.startAndAwait()
        verify(f.ledger, times(1)).insert(any())
    }

    @Test fun `unknown total is indeterminate and progress is bounded`() {
        val row = transfer(NasTransferStatus.IN_PROGRESS)
        assertThat(row.copy(totalBytes = 0).progressFraction()).isNull()
        assertThat(row.copy(bytesTransferred = 0).progressFraction()).isEqualTo(0f)
        assertThat(row.copy(bytesTransferred = Long.MAX_VALUE).progressFraction()).isEqualTo(1f)
        assertThat(row.copy(bytesTransferred = Long.MAX_VALUE, totalBytes = Long.MAX_VALUE).progressFraction()).isEqualTo(1f)
    }

    @Test fun `latest attempt uses creation before update time and ignores unlinked rows`() {
        val older = transfer(NasTransferStatus.TRANSFERRED).copy(id = "older", createdAt = 1, updatedAt = 999)
        val newer = older.copy(id = "newer", createdAt = 2, updatedAt = 3, status = NasTransferStatus.PENDING)
        val tieWinner = newer.copy(id = "tieWinner", updatedAt = 4)
        val unrelated = newer.copy(id = "unrelated", downloadId = "other")
        val unlinked = newer.copy(id = "unlinked", downloadId = null, createdAt = 999)
        val f = Fixture(download(), listOf(tieWinner, older, newer, unrelated, unlinked))
        assertThat(f.vm.uiState.value.nasTransfersByDownloadId)
            .containsExactly("download", tieWinner, "other", unrelated)
    }

    private class Fixture(
        val item: DownloadItem,
        transfers: List<NasTransfer> = emptyList(),
        transferFlow: Flow<List<NasTransfer>> = flowOf(transfers),
        downloads: List<DownloadItem> = listOf(item)
    ) {
        val manager = mock<DownloadManager> {
            on { observeAllDownloads() } doReturn flowOf(downloads)
            on { observeStorageState() } doReturn flowOf(DownloadStorageConfig())
        }
        val context = mock<Context> { on { getString(any()) } doReturn "NAS result" }
        val ledger = mock<NasTransferRepository> {
            on { observeAll() } doReturn transferFlow
        }
        val settings = mock<NasTransferSettingsRepository> {
            on { observeSettings() } doReturn flowOf(NasTransferSettings(
                enabled = true, host = "nas", username = "user", remoteDirectory = "/volume1/Media/Films",
                trustedHostKey = NasHostKeyTrust("nas", 22, "ssh-ed25519", "test")
            ))
        }
        val executor = mock<NasTransferExecutor> { onBlocking { execute(any()) } doReturn true }
        val vm = DownloadsViewModel(manager, ledger, settings, executor, context)
        suspend fun startAndAwait() { vm.transferToNas(item); awaitIdle() }
        suspend fun awaitIdle() { withTimeout(5_000) { vm.uiState.first { !it.nasTransferInProgress } } }
    }

    private companion object {
        fun transfer(status: NasTransferStatus) = NasTransfer(
            id = "transfer", downloadId = "download", contentName = "Movie", localFileName = "movie.mkv",
            localSourceUri = "content://opaque/document/123", localSizeBytes = 100,
            remoteDirectory = "/volume1/Media/Films", remoteFinalName = "movie.mkv", remoteTemporaryName = "movie.mkv.part",
            status = status, bytesTransferred = 10, totalBytes = 100, createdAt = 100, updatedAt = 100
        )

        fun download(status: DownloadStatus = DownloadStatus.COMPLETED) = DownloadItem(
            id = "download", providerId = 1, contentType = DownloadContentType.MOVIE, contentId = 1,
            contentName = "Movie", streamUrl = "https://example.invalid/movie", status = status,
            outputUri = "content://opaque/document/123", outputDisplayPath = "/display/folder/movie.mkv",
            bytesWritten = 4992, totalBytes = 9000
        )
    }
}
