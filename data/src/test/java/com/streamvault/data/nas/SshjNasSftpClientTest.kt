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


    @Test
    fun `unknown host key requires explicit confirmation`() = runTest {
        val ssh: SSHClient = mock()
        var verifier: net.schmizz.sshj.transport.verification.HostKeyVerifier? = null

        org.mockito.kotlin.doAnswer { invocation ->
            verifier = invocation.getArgument(0)
            null
        }.`when`(ssh).addHostKeyVerifier(
            org.mockito.kotlin.any<net.schmizz.sshj.transport.verification.HostKeyVerifier>()
        )

        val publicKey = java.security.KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
            .public

        org.mockito.kotlin.doAnswer {
            verifier!!.verify("nas.example", 22, publicKey)
            throw java.io.IOException("Host key rejected")
        }.`when`(ssh).connect("nas.example", 22)

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

        assertThat(result)
            .isInstanceOf(NasSftpResult.HostKeyConfirmationRequired::class.java)

        val confirmation = result as NasSftpResult.HostKeyConfirmationRequired
        assertThat(confirmation.trust.host).isEqualTo("nas.example")
        assertThat(confirmation.trust.port).isEqualTo(22)
    }


    @Test
    fun `changed host key is mapped to HOST_KEY_CHANGED`() = runTest {
        val ssh: SSHClient = mock()
        var verifier: net.schmizz.sshj.transport.verification.HostKeyVerifier? = null

        org.mockito.kotlin.doAnswer { invocation ->
            verifier = invocation.getArgument(0)
            null
        }.`when`(ssh).addHostKeyVerifier(
            org.mockito.kotlin.any<net.schmizz.sshj.transport.verification.HostKeyVerifier>()
        )

        val publicKey = java.security.KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
            .public

        org.mockito.kotlin.doAnswer {
            verifier!!.verify("nas.example", 22, publicKey)
            throw java.io.IOException("Host key rejected")
        }.`when`(ssh).connect("nas.example", 22)

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
                trustedHostKey = com.streamvault.domain.model.NasHostKeyTrust(
                    host = "nas.example",
                    port = 22,
                    algorithm = "RSA",
                    fingerprint = "old-fingerprint"
                )
            )
        )

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.HOST_KEY_CHANGED)
    }
}
