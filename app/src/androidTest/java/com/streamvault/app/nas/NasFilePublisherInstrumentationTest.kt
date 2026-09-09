package com.streamvault.app.nas

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.domain.manager.NasFilePublisher
import com.streamvault.domain.model.NasPublicationResult
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
class NasFilePublisherInstrumentationTest {
    @Test
    fun publishesLocalFileUsingSavedNasCredentials() {
        // Must precede application dependency access, credential reads and every network call.
        assumeTrue(
            "Live NAS publication requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("streamvaultLiveNasPublish") == "true"
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
                val name = "streamvault-publish-test-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt"
                val directory = File(context.filesDir, "nas-file-publisher-test")
                assertTrue("Cannot create local test directory", directory.isDirectory || directory.mkdirs())
                val localFile = File(directory, name)
                assertTrue("Cannot exclusively create local test file", localFile.createNewFile())
                val content = "StreamVault NAS physical publication test.\n".repeat(128).toByteArray(Charsets.UTF_8)
                localFile.outputStream().use { it.write(content) }
                val expectedSize = localFile.length()
                assertEquals("Unexpected local test size", content.size.toLong(), expectedSize)
                assertTrue("Local test file must not be empty", expectedSize > 0L)

                val source = LocalFileSource(localFile)
                val finalPath = "$REMOTE_DIRECTORY/$name"
                val connection = NasSftpConnection(settings, password, trust)
                val client = entry.nasSftpClient()
                Log.i("NasPublisherTest", "file=$name size=$expectedSize")
                val publisher = NasFilePublisher(entry.nasSftpClient())
                when (val result = publisher.publish(connection, source, finalPath)) {
                    NasPublicationResult.Success -> Log.i("NasPublisherTest", "result=Success")
                    NasPublicationResult.AlreadyPresent -> throw AssertionError("AlreadyPresent")
                    NasPublicationResult.Conflict -> throw AssertionError("Conflict")
                    is NasPublicationResult.Failure -> throw AssertionError(
                        "reason=${result.reason.name}" +
                            (result.sftpError?.let { " sftpError=${it.name}" } ?: "")
                    )
                }
                // Independent read-only checks after the engine has completed publication.
                val remoteSize = requireSuccess(client.getRemoteSize(connection, finalPath))
                    ?: throw AssertionError("Remote test file unavailable")
                assertEquals("Remote size mismatch", expectedSize, remoteSize)
                assertEquals("Remote part must be absent", false, requireSuccess(client.exists(connection, "$finalPath.part")))
                assertTrue("Local test file must be preserved", localFile.isFile)
                assertEquals("Local test file size changed", expectedSize, localFile.length())
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
        override val uriString: String = "nas-file-publisher-test:${file.name}"

        override suspend fun openInputStream(): InputStream = file.inputStream()

        override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? =
            throw AssertionError("Physical publication test must not seek the source")

        override suspend fun deleteAfterValidatedTransfer(): Boolean =
            throw AssertionError("Physical publication test must not delete the local source")
    }

    private companion object {
        const val REMOTE_DIRECTORY = "/volume1/Media/Films"
    }
}
