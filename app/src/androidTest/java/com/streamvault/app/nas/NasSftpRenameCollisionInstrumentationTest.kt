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
class NasSftpRenameCollisionInstrumentationTest {
    @Test
    fun refusesRenameOverExistingDestinationUsingSavedNasCredentials() {
        // Must precede application dependency access, credential reads and every network call.
        assumeTrue(
            "Live NAS rename collision requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("streamvaultLiveNasRenameCollision") == "true"
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
                val suffix = "${System.currentTimeMillis()}-${UUID.randomUUID()}.txt"
                val sourceName = "streamvault-rename-source-$suffix"
                val destinationName = "streamvault-rename-destination-$suffix"
                val directory = File(context.filesDir, "nas-rename-collision-test")
                assertTrue("Cannot create local test directory", directory.isDirectory || directory.mkdirs())
                val sourceFile = File(directory, sourceName)
                val destinationFile = File(directory, destinationName)
                assertTrue("Cannot exclusively create local source", sourceFile.createNewFile())
                assertTrue("Cannot exclusively create local destination", destinationFile.createNewFile())
                sourceFile.outputStream().use {
                    it.write("StreamVault rename collision source.\n".repeat(128).toByteArray(Charsets.UTF_8))
                }
                destinationFile.outputStream().use {
                    it.write("StreamVault rename collision destination.\n".toByteArray(Charsets.UTF_8))
                }
                val sourceSize = sourceFile.length()
                val destinationSize = destinationFile.length()
                assertTrue("Source must be larger than nonempty destination", sourceSize > destinationSize && destinationSize > 0L)

                val remoteSourcePath = "$REMOTE_DIRECTORY/$sourceName"
                val remoteDestinationPath = "$REMOTE_DIRECTORY/$destinationName"
                val connection = NasSftpConnection(settings, password, trust)
                val client = entry.nasSftpClient()
                Log.i("NasRenameCollisionTest", "source=$sourceName size=$sourceSize destination=$destinationName size=$destinationSize")

                val uploadedSourceBytes = requireSuccess(client.upload(connection, LocalFileSource(sourceFile), remoteSourcePath))
                assertEquals("Uploaded source size mismatch", sourceSize, uploadedSourceBytes)
                assertEquals("Remote source size mismatch", sourceSize, requireSuccess(client.getRemoteSize(connection, remoteSourcePath)))

                val uploadedDestinationBytes = requireSuccess(client.upload(connection, LocalFileSource(destinationFile), remoteDestinationPath))
                assertEquals("Uploaded destination size mismatch", destinationSize, uploadedDestinationBytes)
                assertEquals("Remote destination size mismatch", destinationSize, requireSuccess(client.getRemoteSize(connection, remoteDestinationPath)))

                when (client.rename(connection, remoteSourcePath, remoteDestinationPath)) {
                    is NasSftpResult.Failure -> Log.i("NasRenameCollisionTest", "rename=Failure")
                    is NasSftpResult.Success -> {
                        Log.i("NasRenameCollisionTest", "rename=Success (unexpected)")
                        // Attempt both reads independently before failing; never restore or delete.
                        val sourceObservation = observeRemoteSize { client.getRemoteSize(connection, remoteSourcePath) }
                        val destinationObservation = observeRemoteSize { client.getRemoteSize(connection, remoteDestinationPath) }
                        throw AssertionError(
                            "Rename unexpectedly succeeded; source=$sourceName $sourceObservation; " +
                                "destination=$destinationName $destinationObservation"
                        )
                    }
                    is NasSftpResult.HostKeyConfirmationRequired ->
                        throw AssertionError("NAS host key confirmation required; no approval performed")
                }

                val remainingSourceSize = requireSuccess(client.getRemoteSize(connection, remoteSourcePath))
                val remainingDestinationSize = requireSuccess(client.getRemoteSize(connection, remoteDestinationPath))
                assertEquals("Source size changed after refused rename", sourceSize, remainingSourceSize)
                assertEquals("Destination size changed after refused rename", destinationSize, remainingDestinationSize)
                assertTrue("Local source must be preserved", sourceFile.isFile)
                assertTrue("Local destination must be preserved", destinationFile.isFile)
                assertEquals("Local source size changed", sourceSize, sourceFile.length())
                assertEquals("Local destination size changed", destinationSize, destinationFile.length())
                // Deliberately no local or remote cleanup, including on failure.
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private suspend fun observeRemoteSize(read: suspend () -> NasSftpResult<Long?>): String = try {
        when (val result = read()) {
            is NasSftpResult.Success -> result.value?.let { "exists=true size=$it" } ?: "exists=false size=null"
            is NasSftpResult.Failure -> "size unavailable (read failed)"
            is NasSftpResult.HostKeyConfirmationRequired -> "size unavailable (host key confirmation required)"
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Keep diagnostics independent and never expose an exception containing connection data.
        "size unavailable (read failed)"
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
        override val uriString: String = "nas-rename-collision-test:${file.name}"

        override suspend fun openInputStream(): InputStream = file.inputStream()

        override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? =
            throw AssertionError("Physical rename collision test must not seek the source")

        override suspend fun deleteAfterValidatedTransfer(): Boolean =
            throw AssertionError("Physical rename collision test must not delete the local source")
    }

    private companion object {
        const val REMOTE_DIRECTORY = "/volume1/Media/Films"
    }
}
