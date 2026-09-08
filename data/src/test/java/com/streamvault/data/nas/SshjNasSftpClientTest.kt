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


    @Test
    fun `missing remote directory is mapped to REMOTE_DIRECTORY_NOT_FOUND`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films")).thenReturn(null)

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
            .isEqualTo(NasSftpError.REMOTE_DIRECTORY_NOT_FOUND)
    }


    @Test
    fun `unwritable remote directory is mapped to REMOTE_DIRECTORY_NOT_WRITABLE`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)

        org.mockito.kotlin.doThrow(
            net.schmizz.sshj.sftp.SFTPException("Permission denied")
        ).`when`(sftp).open(
            org.mockito.kotlin.any<String>(),
            org.mockito.kotlin.any()
        )

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
            .isEqualTo(NasSftpError.REMOTE_DIRECTORY_NOT_WRITABLE)
    }


    @Test
    fun `temporary write test cleanup failure is mapped to REMOTE_TEST_CLEANUP_FAILED`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()
        val remoteFile: net.schmizz.sshj.sftp.RemoteFile = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(
            sftp.open(
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any()
            )
        ).thenReturn(remoteFile)

        org.mockito.kotlin.doThrow(java.io.IOException("Cleanup failed"))
            .`when`(sftp)
            .rm(org.mockito.kotlin.any())

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
            .isEqualTo(NasSftpError.REMOTE_TEST_CLEANUP_FAILED)
    }


    @Test
    fun `valid remote directory returns successful connection test`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()
        val remoteFile: net.schmizz.sshj.sftp.RemoteFile = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(
            sftp.open(
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any()
            )
        ).thenReturn(remoteFile)

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

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        val success = result as NasSftpResult.Success
        assertThat(success.value.directoryVerified).isTrue()
        assertThat(success.value.writeVerified).isTrue()
    }


    @Test
    fun `stat returns remote file metadata`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/movie.mkv"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.size).thenReturn(123456L)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.REGULAR)

        val client = SshjNasSftpClient { ssh }

        val result = client.stat(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/movie.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)

        val remoteFile = (result as NasSftpResult.Success).value
        assertThat(remoteFile).isNotNull()
        assertThat(remoteFile!!.path).isEqualTo("/films/movie.mkv")
        assertThat(remoteFile.sizeBytes).isEqualTo(123456L)
        assertThat(remoteFile.isDirectory).isFalse()
    }


    @Test
    fun `stat returns null when remote path does not exist`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/missing.mkv"))
            .thenReturn(null)

        val client = SshjNasSftpClient { ssh }

        val result = client.stat(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/missing.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isNull()
    }


    @Test
    fun `exists returns true when remote path exists`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/movie.mkv"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.size).thenReturn(123456L)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.REGULAR)

        val client = SshjNasSftpClient { ssh }

        val result = client.exists(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/movie.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isTrue()
    }


    @Test
    fun `exists returns false when remote path does not exist`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/missing.mkv"))
            .thenReturn(null)

        val client = SshjNasSftpClient { ssh }

        val result = client.exists(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/missing.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isFalse()
    }


    @Test
    fun `getRemoteSize returns file size when remote path exists`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/movie.mkv"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.size).thenReturn(123456L)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.REGULAR)

        val client = SshjNasSftpClient { ssh }

        val result = client.getRemoteSize(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/movie.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isEqualTo(123456L)
    }


    @Test
    fun `getRemoteSize returns null when remote path does not exist`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films/missing.mkv"))
            .thenReturn(null)

        val client = SshjNasSftpClient { ssh }

        val result = client.getRemoteSize(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films/missing.mkv"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isNull()
    }


    @Test
    fun `isDirectoryWritable returns false when remote directory does not exist`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(null)

        val client = SshjNasSftpClient { ssh }

        val result = client.isDirectoryWritable(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isFalse()
    }


    @Test
    fun `isDirectoryWritable returns false when remote path is not a directory`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.REGULAR)

        val client = SshjNasSftpClient { ssh }

        val result = client.isDirectoryWritable(
            NasSftpConnection(
                settings = NasTransferSettings(
                    host = "nas.example",
                    port = 22,
                    username = "streamvault",
                    remoteDirectory = "/films"
                ),
                password = "secret".toCharArray(),
                trustedHostKey = null
            ),
            "/films"
        )

        assertThat(result).isInstanceOf(NasSftpResult.Success::class.java)
        assertThat((result as NasSftpResult.Success).value).isFalse()
    }
}
