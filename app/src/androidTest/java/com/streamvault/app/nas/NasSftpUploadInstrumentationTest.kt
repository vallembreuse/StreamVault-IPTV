package com.streamvault.app.nas

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpResult
import com.streamvault.domain.repository.NasTransferSource
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: leaves the local and remote test files available for manual inspection. */
@RunWith(AndroidJUnit4::class)
class NasSftpUploadInstrumentationTest {
    @Test
    fun uploadsKnownLocalFileUsingSavedNasCredentials() {
        // Must precede application dependency access, credential reads and every network call.
        assumeTrue(
            "Live NAS upload requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("streamvaultLiveNasUpload") == "true"
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        runBlocking(Dispatchers.IO) {
            val entry = EntryPointAccessors.fromApplication(context, NasUploadTestEntryPoint::class.java)
            val settings = entry.nasTransferSettingsRepository().observeSettings().first()
            assertTrue("NAS configuration invalid", settings.validationErrors(requirePassword = false).isEmpty())
            assertTrue("NAS host unavailable", settings.host.isNotBlank())
            assertTrue("NAS port invalid", settings.port in 1..65_535)
            assertTrue("NAS username unavailable", settings.username.isNotBlank())
            assertEquals("Unexpected NAS directory", REMOTE_DIRECTORY, settings.remoteDirectory)
            val trust = settings.trustedHostKey ?: throw AssertionError("Approved NAS host key unavailable")
            assertTrue("Approved NAS host key does not match endpoint", trust.host == settings.host && trust.port == settings.port)

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue("Existing NAS credential key unavailable", keyStore.containsAlias("streamvault_credentials"))

            val password = try {
                entry.nasCredentialStore().readPassword()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never attach credential values or the original exception to a test failure.
                throw AssertionError("NAS credential unavailable")
            } ?: throw AssertionError("NAS credential unavailable")

            try {
                if (password.isEmpty()) throw AssertionError("NAS credential unavailable")
                val name = "streamvault-upload-test-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt"
                val directory = File(context.filesDir, "nas-upload-test")
                assertTrue("Cannot create local test directory", directory.isDirectory || directory.mkdirs())
                val localFile = File(directory, name)
                assertTrue("Cannot exclusively create local test file", localFile.createNewFile())
                val sizeMiB = InstrumentationRegistry.getArguments()
                    .getString("streamvaultLiveNasUploadSizeMiB")
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: 1
                val targetSize = sizeMiB.toLong() * 1024L * 1024L
                val block = ByteArray(1024 * 1024) { (it % 251).toByte() }
                localFile.outputStream().buffered().use { output ->
                    repeat(sizeMiB) {
                        output.write(block)
                    }
                }
                val originalSize = localFile.length()
                assertEquals("Unexpected local test size", targetSize, originalSize)
                assertTrue("Local test file must not be empty", originalSize > 0L)

                val source = LocalFileSource(localFile)
                val remotePath = "$REMOTE_DIRECTORY/$name"
                val connection = NasSftpConnection(settings, password, trust)
                val client = entry.nasSftpClient()
                Log.i("NasUploadTest", "file=$name size=$originalSize")
                val startedAt = System.nanoTime()
                val uploadedBytes = requireSuccess(client.upload(connection, source, remotePath))
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                val throughputMiBPerSecond =
                    (uploadedBytes / 1024.0 / 1024.0) / elapsedSeconds
                Log.i(
                    "NasUploadTest",
                    "uploadedBytes=$uploadedBytes elapsedSeconds=$elapsedSeconds throughputMiBPerSecond=$throughputMiBPerSecond"
                )
                assertEquals("Uploaded size mismatch", originalSize, uploadedBytes)
                val remoteSize = requireSuccess(client.getRemoteSize(connection, remotePath))
                    ?: throw AssertionError("Remote test file unavailable")
                assertEquals("Remote size mismatch", originalSize, remoteSize)
                assertTrue("Local test file must be preserved", localFile.isFile)
                assertEquals("Local test file size changed", originalSize, localFile.length())
                // Deliberately no local or remote cleanup, including on failure.
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun <T> requireSuccess(result: NasSftpResult<T>): T = when (result) {
        is NasSftpResult.Success -> result.value
        is NasSftpResult.Failure -> throw AssertionError(result.error.name)
        is NasSftpResult.HostKeyConfirmationRequired ->
            throw AssertionError("NAS host key confirmation required; no approval performed")
    }

    private class LocalFileSource(private val file: File) : NasTransferSource {
        override val displayName: String = file.name
        override val sizeBytes: Long = file.length()
        override val uriString: String = "nas-upload-test:${file.name}"

        override suspend fun openInputStream(): InputStream = file.inputStream()

        override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? =
            throw AssertionError("Physical upload test must not seek the source")

        override suspend fun deleteAfterValidatedTransfer(): Boolean =
            throw AssertionError("Physical upload test must not delete the local source")
    }

    private companion object {
        const val REMOTE_DIRECTORY = "/volume1/Media/Films"
    }
}
