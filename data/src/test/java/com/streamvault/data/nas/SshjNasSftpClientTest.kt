package com.streamvault.data.nas

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpError
import com.streamvault.domain.repository.NasSftpResult
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

class SshjNasSftpClientTest {

    @Test
    fun `connection refused is mapped to CONNECTION_REFUSED`() = runTest {
        val ssh: SSHClient = mock()

        doThrow(java.net.ConnectException("Connection refused"))
            .`when`(ssh)
            .connect("nas.example", 22)

        val client = SshjNasSftpClient { ssh }

        val result = client.testConnection(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            )
        )

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.CONNECTION_REFUSED)
    }

    @Test
    fun `authentication failure is mapped to AUTHENTICATION_FAILED`() = runTest {
        val ssh: SSHClient = mock()

        doThrow(net.schmizz.sshj.userauth.UserAuthException("Authentication failed"))
            .`when`(ssh)
            .authPassword("streamvault", "secret".toCharArray())

        val client = SshjNasSftpClient { ssh }

        val result = client.testConnection(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            )
        )

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.AUTHENTICATION_FAILED)
    }


    @Test
    fun `connection timeout is mapped to TIMEOUT`() = runTest {
        val ssh: SSHClient = mock()

        doThrow(java.net.SocketTimeoutException("Connection timed out"))
            .`when`(ssh)
            .connect("nas.example", 22)

        val client = SshjNasSftpClient { ssh }

        val result = client.testConnection(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            )
        )

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.TIMEOUT)
    }


    @Test
    fun `unknown host is mapped to DNS_OR_HOST_UNREACHABLE`() = runTest {
        val ssh: SSHClient = mock()

        doThrow(java.net.UnknownHostException("nas.example"))
            .`when`(ssh)
            .connect("nas.example", 22)

        val client = SshjNasSftpClient { ssh }

        val result = client.testConnection(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            )
        )

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.DNS_OR_HOST_UNREACHABLE)
    }
}
