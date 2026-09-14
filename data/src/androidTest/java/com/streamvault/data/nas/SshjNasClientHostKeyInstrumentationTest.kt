package com.streamvault.data.nas

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshjNasClientHostKeyInstrumentationTest {

    @Test
    fun unknownRealNasHostKeyReturnsConfirmationRequired() = runBlocking {
        val settings = NasTransferSettings(
            enabled = true,
            host = "192.168.1.26",
            port = 22,
            username = "dummy",
            remoteDirectory = "/"
        )

        val connection = NasSftpConnection(
            settings = settings,
            password = "dummy".toCharArray(),
            trustedHostKey = null
        )

        val result = SshjNasSftpClient().testConnection(connection)

        assertTrue(
            "Expected HostKeyConfirmationRequired, got: $result",
            result is NasSftpResult.HostKeyConfirmationRequired
        )
    }
}
