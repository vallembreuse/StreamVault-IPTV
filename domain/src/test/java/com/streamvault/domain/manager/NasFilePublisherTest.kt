package com.streamvault.domain.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.model.NasPublicationFailureReason.*
import com.streamvault.domain.model.NasPublicationResult
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasConnectionTestResult
import com.streamvault.domain.repository.NasRemoteFile
import com.streamvault.domain.repository.NasSftpClient
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpError
import com.streamvault.domain.repository.NasSftpResult
import com.streamvault.domain.repository.NasTransferSource
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NasFilePublisherTest {
    @Test
    fun `success follows exact publication order with the same connection and source`() = runTest {
        val fake = FakeClient()
        assertThat(fake.publish()).isEqualTo(NasPublicationResult.Success)
        assertThat(fake.calls).containsExactlyElementsIn(ORDER).inOrder()
        assertThat(fake.source.sizeReads).isEqualTo(1)
        assertThat(fake.connection.password.toList()).containsExactly('t', 'e', 's', 't').inOrder()
    }

    @Test
    fun `initial final of same size stops as already present`() = runTest {
        checkOutcome(0, NasSftpResult.Success(remote()), NasPublicationResult.AlreadyPresent)
    }

    @Test
    fun `initial final of different size stops as conflict`() = runTest {
        checkOutcome(0, NasSftpResult.Success(remote(size = SIZE + 1)), NasPublicationResult.Conflict)
    }

    @Test
    fun `initial directory conflicts even with matching size`() = runTest {
        checkOutcome(0, NasSftpResult.Success(remote(directory = true)), NasPublicationResult.Conflict)
    }

    @Test
    fun `existing part prevents upload`() = runTest {
        checkOutcome(1, NasSftpResult.Success(true), NasPublicationResult.Failure(PART_ALREADY_EXISTS))
    }

    @Test
    fun `incorrect uploaded count prevents rename`() = runTest {
        checkOutcome(2, NasSftpResult.Success(SIZE - 1), NasPublicationResult.Failure(SIZE_MISMATCH))
    }

    @Test
    fun `incorrect or missing remote part size prevents rename`() = runTest {
        for (size in listOf(SIZE + 1, null)) {
            checkOutcome(3, NasSftpResult.Success(size), NasPublicationResult.Failure(SIZE_MISMATCH))
        }
    }

    @Test
    fun `matching final appearing before rename stops as already present`() = runTest {
        checkOutcome(4, NasSftpResult.Success(remote()), NasPublicationResult.AlreadyPresent)
    }

    @Test
    fun `different final appearing before rename stops as conflict`() = runTest {
        checkOutcome(4, NasSftpResult.Success(remote(size = SIZE + 1)), NasPublicationResult.Conflict)
    }

    @Test
    fun `directory appearing before rename stops as conflict`() = runTest {
        checkOutcome(4, NasSftpResult.Success(remote(directory = true)), NasPublicationResult.Conflict)
    }

    @Test
    fun `rename refusal makes exactly one attempt`() = runTest {
        val fake = checkOutcome(
            5, NasSftpResult.Failure(NasSftpError.UNKNOWN),
            NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.UNKNOWN)
        )
        assertThat(fake.calls.count { it.startsWith("rename(") }).isEqualTo(1)
    }

    @Test
    fun `missing final after rename fails`() = runTest {
        checkOutcome(6, NasSftpResult.Success(null), NasPublicationResult.Failure(FINAL_MISSING))
    }

    @Test
    fun `wrong final size after rename fails`() = runTest {
        checkOutcome(6, NasSftpResult.Success(SIZE + 1), NasPublicationResult.Failure(SIZE_MISMATCH))
    }

    @Test
    fun `part remaining after rename fails`() = runTest {
        checkOutcome(7, NasSftpResult.Success(true), NasPublicationResult.Failure(PART_STILL_PRESENT))
    }

    @Test
    fun `SFTP errors at every stage stop immediately and retain their category`() = runTest {
        for (step in ORDER.indices) {
            for (error in NasSftpError.entries) {
                checkOutcome(step, NasSftpResult.Failure(error), NasPublicationResult.Failure(SFTP_FAILURE, error))
            }
        }
    }

    @Test
    fun `host key confirmation at every stage fails without approval`() = runTest {
        val trust = NasHostKeyTrust("nas.example", 22, "ssh-ed25519", "test-fingerprint")
        for (step in ORDER.indices) {
            checkOutcome(
                step, NasSftpResult.HostKeyConfirmationRequired(trust),
                NasPublicationResult.Failure(SFTP_FAILURE, NasSftpError.HOST_KEY_UNKNOWN)
            )
        }
    }

    @Test
    fun `cancellation at every stage propagates unchanged`() = runTest {
        for (step in ORDER.indices) {
            val cancellation = CancellationException("test cancellation")
            val fake = FakeClient(cancelAt = step, cancellation = cancellation)
            try {
                fake.publish()
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertThat(actual).isSameInstanceAs(cancellation)
            }
            assertThat(fake.calls).containsExactlyElementsIn(ORDER.take(step + 1)).inOrder()
        }
    }

    @Test
    fun `negative source size and blank paths fail before SFTP`() = runTest {
        for ((size, path) in listOf(-1L to FINAL, SIZE to "", SIZE to "  \t\n")) {
            val fake = FakeClient(source = FakeSource(size))
            assertThat(fake.publish(path)).isEqualTo(NasPublicationResult.Failure(INVALID_INPUT))
            assertThat(fake.calls).isEmpty()
            assertThat(fake.source.sizeReads).isEqualTo(1)
        }
    }

    @Test
    fun `zero byte source can be published`() = runTest {
        val fake = FakeClient(source = FakeSource(0L))
        fake.responses[2] = NasSftpResult.Success(0L)
        fake.responses[3] = NasSftpResult.Success(0L)
        fake.responses[6] = NasSftpResult.Success(0L)
        assertThat(fake.publish()).isEqualTo(NasPublicationResult.Success)
        assertThat(fake.calls).containsExactlyElementsIn(ORDER).inOrder()
    }

    private suspend fun checkOutcome(
        step: Int,
        response: NasSftpResult<*>,
        expected: NasPublicationResult
    ): FakeClient {
        val fake = FakeClient()
        fake.responses[step] = response
        assertThat(fake.publish()).isEqualTo(expected)
        assertThat(fake.calls).containsExactlyElementsIn(ORDER.take(step + 1)).inOrder()
        assertThat(fake.source.sizeReads).isEqualTo(1)
        return fake
    }

    private class FakeClient(
        val source: FakeSource = FakeSource(),
        private val cancelAt: Int? = null,
        private val cancellation: CancellationException = CancellationException("test cancellation")
    ) : NasSftpClient {
        val connection = NasSftpConnection(
            NasTransferSettings(host = "nas.example", username = "test", remoteDirectory = "/films"),
            charArrayOf('t', 'e', 's', 't'),
            null
        )
        val calls = mutableListOf<String>()
        val responses = mutableListOf<NasSftpResult<*>>(
            NasSftpResult.Success(null), NasSftpResult.Success(false),
            NasSftpResult.Success(SIZE), NasSftpResult.Success(SIZE),
            NasSftpResult.Success(null), NasSftpResult.Success(Unit),
            NasSftpResult.Success(SIZE), NasSftpResult.Success(false)
        )

        suspend fun publish(path: String = FINAL): NasPublicationResult =
            NasFilePublisher(this).publish(connection, source, path)

        @Suppress("UNCHECKED_CAST")
        private fun <T> answer(connection: NasSftpConnection, call: String): NasSftpResult<T> {
            assertThat(connection).isSameInstanceAs(this.connection)
            val step = calls.size
            calls.add(call)
            assertThat(call).isEqualTo(ORDER.getOrNull(step))
            if (step == cancelAt) throw cancellation
            return responses[step] as NasSftpResult<T>
        }

        override suspend fun stat(connection: NasSftpConnection, remotePath: String): NasSftpResult<NasRemoteFile?> =
            answer(connection, "stat($remotePath)")

        override suspend fun exists(connection: NasSftpConnection, remotePath: String): NasSftpResult<Boolean> =
            answer(connection, "exists($remotePath)")

        override suspend fun upload(connection: NasSftpConnection, source: NasTransferSource, remotePath: String): NasSftpResult<Long> {
            assertThat(source).isSameInstanceAs(this.source)
            return answer(connection, "upload($remotePath)")
        }

        override suspend fun getRemoteSize(connection: NasSftpConnection, remotePath: String): NasSftpResult<Long?> =
            answer(connection, "getRemoteSize($remotePath)")

        override suspend fun rename(connection: NasSftpConnection, sourcePath: String, destinationPath: String): NasSftpResult<Unit> =
            answer(connection, "rename($sourcePath, $destinationPath)")

        override suspend fun testConnection(connection: NasSftpConnection): NasSftpResult<NasConnectionTestResult> =
            throw AssertionError("Publication must not probe the connection")

        override suspend fun isDirectoryWritable(connection: NasSftpConnection, remoteDirectory: String): NasSftpResult<Boolean> =
            throw AssertionError("Publication must not run a destructive write probe")
    }

    private class FakeSource(private val size: Long = SIZE) : NasTransferSource {
        var sizeReads = 0
        override val sizeBytes: Long
            get() {
                sizeReads++
                return size
            }
        override val displayName = "movie.mkv"
        override val uriString = "test:movie.mkv"
        override suspend fun openInputStream(): InputStream =
            throw AssertionError("Only the SFTP upload implementation may open the source")
        override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? =
            throw AssertionError("Publication must not resume")
        override suspend fun deleteAfterValidatedTransfer(): Boolean =
            throw AssertionError("Publication must preserve the local source")
    }

    private companion object {
        const val FINAL = "/films/movie.mkv"
        const val PART = "$FINAL.part"
        const val SIZE = 4992L
        val ORDER = listOf(
            "stat($FINAL)", "exists($PART)", "upload($PART)", "getRemoteSize($PART)",
            "stat($FINAL)", "rename($PART, $FINAL)", "getRemoteSize($FINAL)", "exists($PART)"
        )
        fun remote(size: Long = SIZE, directory: Boolean = false) = NasRemoteFile(FINAL, size, directory)
    }
}
