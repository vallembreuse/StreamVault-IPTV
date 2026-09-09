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
        verifyNoInteractions(f.ledger, f.executor)
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
            verifyNoInteractions(f.ledger, f.executor, f.settings)
        }
    }

    @Test fun `missing URI or file name refuses without executor`() = runBlocking<Unit> {
        for (item in listOf(download().copy(outputUri = null), download().copy(outputDisplayPath = null))) {
            val f = Fixture(item)
            f.startAndAwait()
            verifyNoInteractions(f.ledger, f.executor)
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
            f.vm.transferToNas(f.item)
        } finally { release.complete(Unit) }
        f.awaitIdle()
        verify(f.ledger, times(1)).insert(any())
        verify(f.executor, times(1)).execute(any())
    }

    private class Fixture(val item: DownloadItem) {
        val manager = mock<DownloadManager> {
            on { observeAllDownloads() } doReturn flowOf(listOf(item))
            on { observeStorageState() } doReturn flowOf(DownloadStorageConfig())
        }
        val context = mock<Context> { on { getString(any()) } doReturn "NAS result" }
        val ledger = mock<NasTransferRepository>()
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
        fun download(status: DownloadStatus = DownloadStatus.COMPLETED) = DownloadItem(
            id = "download", providerId = 1, contentType = DownloadContentType.MOVIE, contentId = 1,
            contentName = "Movie", streamUrl = "https://example.invalid/movie", status = status,
            outputUri = "content://opaque/document/123", outputDisplayPath = "/display/folder/movie.mkv",
            bytesWritten = 4992, totalBytes = 9000
        )
    }
}
