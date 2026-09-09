package com.streamvault.data.nas

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.NasTransferEntity
import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasTransferStatus
import com.streamvault.domain.model.NasTransferStatus.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasTransferRepositoryImplTest {
    private lateinit var database: StreamVaultDatabase
    private lateinit var repository: NasTransferRepositoryImpl

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StreamVaultDatabase::class.java
        ).build()
        repository = NasTransferRepositoryImpl(database)
    }

    @After fun tearDown() { database.close() }

    @Test fun insertAndReadPreserveEveryField() = runBlocking {
        val initial = transfer()
        repository.insert(initial)
        assertEquals(initial, repository.getById(initial.id))
        assertNull(repository.getById("missing"))
    }

    @Test fun invalidInitialValuesAreRejected() = runBlocking {
        val t = transfer()
        val invalid = listOf(
            t.copy(status = IN_PROGRESS), t.copy(bytesTransferred = 1),
            t.copy(totalBytes = t.totalBytes + 1), t.copy(lastError = "BUSINESS_ERROR"),
            t.copy(completedAt = 1), t.copy(updatedAt = 2), t.copy(contentName = " "),
            t.copy(localFileName = " "), t.copy(remoteDirectory = " "),
            t.copy(remoteFinalName = " "), t.copy(remoteTemporaryName = " ")
        )
        for (row in invalid) {
            try {
                repository.insert(row)
                fail("Expected invalid initial transfer to be rejected")
            } catch (_: IllegalArgumentException) { }
            assertNull(repository.getById(t.id))
        }
        // The domain constructor itself rejects blank id/URI and negative sizes.
        for (construct in listOf<() -> NasTransfer>(
            { t.copy(id = " ") }, { t.copy(localSourceUri = " ") },
            { t.copy(localSizeBytes = -1) }, { t.copy(totalBytes = -1) }
        )) {
            try { repository.insert(construct()); fail("Expected invalid model") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun duplicateIdDoesNotReplaceExistingRow() = runBlocking {
        val t = transfer()
        repository.insert(t)
        try {
            repository.insert(t.copy(contentName = "Different"))
            fail("Expected duplicate id to fail")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(t, repository.getById(t.id))
    }

    @Test fun recoverableQueueFiltersAllStatusesAndPreservesCreationOrder() = runBlocking {
        val rows = NasTransferStatus.entries.mapIndexed { index, status ->
            transfer(status.name, (100 - index).toLong()).inState(status)
        }
        // Seed all states directly to isolate repository query/mapping from transition behavior.
        for (row in rows) database.nasTransferDao().insert(NasTransferEntity.fromDomain(row))
        val expected = rows.filter { it.status in setOf(PENDING, IN_PROGRESS, INTERRUPTED) }.sortedBy { it.createdAt }
        assertEquals(expected, repository.observeRecoverableQueue().first())
    }

    @Test fun recoverableQueueEmitsAfterStateChange() = runBlocking {
        val t = transfer()
        repository.insert(t)
        withTimeout(5_000) {
            val observation = async { repository.observeRecoverableQueue().first { it.isEmpty() } }
            assertTrue(repository.update(t.inState(CONFLICT)))
            assertTrue(observation.await().isEmpty())
        }
    }

    @Test fun pendingToInProgressIsAccepted() = runBlocking {
        val t = transfer()
        repository.insert(t)
        val next = t.inState(IN_PROGRESS)
        assertTrue(repository.update(next))
        assertEquals(next, repository.getById(t.id))
    }

    @Test fun inProgressToTransferredPreservesAllFields() = runBlocking {
        val t = transfer()
        repository.insert(t)
        assertTrue(repository.update(t.inState(IN_PROGRESS)))
        val next = t.inState(TRANSFERRED)
        assertTrue(repository.update(next))
        assertEquals(next, repository.getById(t.id))
    }

    @Test fun transferredCannotReturnToPending() = runBlocking { checkTerminal(TRANSFERRED) }
    @Test fun conflictCannotReturnToPending() = runBlocking { checkTerminal(CONFLICT) }
    @Test fun alreadyPresentCannotReturnToPending() = runBlocking { checkTerminal(ALREADY_PRESENT) }

    @Test fun everyImmutableFieldIsProtected() = runBlocking {
        val t = transfer()
        repository.insert(t)
        val n = t.inState(IN_PROGRESS)
        val changed = listOf(
            n.copy(id = "other"), n.copy(downloadId = null), n.copy(contentName = "other"),
            n.copy(localFileName = "other"), n.copy(localSourceUri = "content://other"),
            n.copy(localSizeBytes = 43), n.copy(remoteDirectory = "/other"),
            n.copy(remoteFinalName = "other"), n.copy(remoteTemporaryName = "other.part"),
            n.copy(totalBytes = 43), n.copy(createdAt = 0)
        )
        for (next in changed) {
            assertFalse(repository.update(next))
            assertEquals(t, repository.getById(t.id))
        }
    }

    @Test fun missingRowReturnsFalse() = runBlocking {
        assertFalse(repository.update(transfer().inState(IN_PROGRESS)))
    }

    @Test fun sameStatusUpdateIsRejected() = runBlocking {
        val t = transfer()
        repository.insert(t)
        assertFalse(repository.update(t.copy(updatedAt = 2)))
        assertEquals(t, repository.getById(t.id))
    }

    @Test fun transferredWithWrongByteCountIsRejected() = runBlocking {
        val t = transfer()
        repository.insert(t)
        val active = t.inState(IN_PROGRESS)
        assertTrue(repository.update(active))
        assertFalse(repository.update(t.inState(TRANSFERRED).copy(bytesTransferred = 1)))
        assertEquals(active, repository.getById(t.id))
    }

    @Test fun wrongTemporaryNameIsRejectedAtInsertion() = runBlocking {
        try {
            repository.insert(transfer().copy(remoteTemporaryName = "wrong.part"))
            fail("Expected temporary name validation")
        } catch (_: IllegalArgumentException) { }
        assertNull(repository.getById("transfer"))
    }

    @Test fun timestampsAndStateInvariantsAreEnforcedWithoutWriting() = runBlocking {
        for (status in listOf(INTERRUPTED, FAILED, TRANSFERRED, CONFLICT, ALREADY_PRESENT)) {
            val t = transfer(status.name)
            repository.insert(t)
            val active = t.inState(IN_PROGRESS)
            assertTrue(repository.update(active))
            val valid = t.inState(status)
            val invalid = mutableListOf(valid.copy(updatedAt = 0))
            if (status in setOf(TRANSFERRED, CONFLICT, ALREADY_PRESENT)) {
                invalid += valid.copy(completedAt = null)
                invalid += valid.copy(lastError = "ERROR")
            } else invalid += valid.copy(completedAt = 2)
            if (status != TRANSFERRED) invalid += valid.copy(bytesTransferred = 1)
            for (next in invalid) {
                assertFalse(repository.update(next))
                assertEquals(active, repository.getById(t.id))
            }
            assertTrue(repository.update(valid))
        }
    }

    @Test fun retryAndInProgressRequireClearedErrorAndZeroBytes() = runBlocking {
        val t = transfer()
        repository.insert(t)
        for (invalid in listOf(
            t.inState(IN_PROGRESS).copy(lastError = "ERROR"),
            t.inState(IN_PROGRESS).copy(bytesTransferred = 1),
            t.inState(IN_PROGRESS).copy(completedAt = 2)
        )) assertFalse(repository.update(invalid))
        val failed = t.inState(FAILED).copy(lastError = "SIZE_MISMATCH")
        assertTrue(repository.update(failed))
        val pending = t.copy(updatedAt = failed.updatedAt)
        for (invalid in listOf(
            pending.copy(lastError = "ERROR"), pending.copy(bytesTransferred = 1), pending.copy(completedAt = 2)
        )) assertFalse(repository.update(invalid))
        assertEquals(failed, repository.getById(t.id))
        assertTrue(repository.update(pending))
        assertEquals(pending, repository.getById(t.id))
    }

    private suspend fun checkTerminal(status: NasTransferStatus) {
        val t = transfer()
        repository.insert(t)
        if (status == TRANSFERRED) assertTrue(repository.update(t.inState(IN_PROGRESS)))
        val terminal = t.inState(status)
        assertTrue(repository.update(terminal))
        assertFalse(repository.update(t.copy(updatedAt = 3)))
        assertEquals(terminal, repository.getById(t.id))
    }

    private fun NasTransfer.inState(next: NasTransferStatus): NasTransfer = copy(
        status = next,
        bytesTransferred = if (next == TRANSFERRED) totalBytes else 0,
        updatedAt = createdAt + 1,
        completedAt = if (next in setOf(TRANSFERRED, CONFLICT, ALREADY_PRESENT)) createdAt + 1 else null
    )

    private fun transfer(id: String = "transfer", time: Long = 1) = NasTransfer(
        id = id, downloadId = "download", contentName = "Movie", localFileName = "movie.mkv",
        localSourceUri = "content://downloads/movie", localSizeBytes = 42,
        remoteDirectory = "/films", remoteFinalName = "movie.mkv", remoteTemporaryName = "movie.mkv.part",
        status = PENDING, bytesTransferred = 0, totalBytes = 42,
        lastError = null, createdAt = time, updatedAt = time, completedAt = null
    )
}
