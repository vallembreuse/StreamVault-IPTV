package com.streamvault.data.nas

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.streamvault.domain.repository.NasTransferSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF/content-URI source adapter for existing downloads. It never invents a path from a URI and
 * therefore works with the persistable tree grant that StreamVault already requests for downloads.
 */
class AndroidNasTransferSource private constructor(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
    override val sizeBytes: Long
) : NasTransferSource {
    override val uriString: String = uri.toString()

    override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Download source is no longer available")
    }

    override suspend fun openInputStreamAt(offsetBytes: Long): InputStream? = withContext(Dispatchers.IO) {
        if (offsetBytes !in 0..sizeBytes) return@withContext null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).also { input ->
                input.channel.position(offsetBytes)
            }
        } catch (_: Exception) {
            runCatching { descriptor.close() }
            null
        }
    }

    override suspend fun deleteAfterValidatedTransfer(): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromSingleUri(context, uri)?.delete() == true
    }

    companion object {
        fun fromPersistedDownload(
            context: Context,
            uriString: String,
            fallbackName: String,
            expectedSizeBytes: Long
        ): AndroidNasTransferSource? {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
            val document = DocumentFile.fromSingleUri(context, uri) ?: return null
            if (!document.exists()) return null
            val size = document.length().takeIf { it >= 0L } ?: expectedSizeBytes
            if (size < 0L) return null
            return AndroidNasTransferSource(
                context = context.applicationContext,
                uri = uri,
                displayName = document.name?.takeIf { it.isNotBlank() } ?: fallbackName,
                sizeBytes = size
            )
        }
    }
}

/** Hilt-ready factory for the later service; it makes no storage permission assumptions. */
@Singleton
class AndroidNasTransferSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun fromPersistedDownload(
        uriString: String,
        fallbackName: String,
        expectedSizeBytes: Long
    ): NasTransferSource? = AndroidNasTransferSource.fromPersistedDownload(
        context = context,
        uriString = uriString,
        fallbackName = fallbackName,
        expectedSizeBytes = expectedSizeBytes
    )
}
