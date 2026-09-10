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


    @Test
    fun `isDirectoryWritable returns true when remote directory accepts write test`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()
        val remoteFile: net.schmizz.sshj.sftp.RemoteFile = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
        org.mockito.kotlin.whenever(
            sftp.open(
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any()
            )
        ).thenReturn(remoteFile)

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
        assertThat((result as NasSftpResult.Success).value).isTrue()
    }


    @Test
    fun `isDirectoryWritable maps write failure to REMOTE_DIRECTORY_NOT_WRITABLE`() = runTest {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()

        org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
        org.mockito.kotlin.whenever(sftp.statExistence("/films"))
            .thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.type)
            .thenReturn(net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)

        org.mockito.kotlin.doThrow(
            net.schmizz.sshj.sftp.SFTPException("Permission denied")
        ).`when`(sftp).open(
            org.mockito.kotlin.any<String>(),
            org.mockito.kotlin.any()
        )

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

        assertThat(result).isInstanceOf(NasSftpResult.Failure::class.java)
        assertThat((result as NasSftpResult.Failure).error)
            .isEqualTo(NasSftpError.REMOTE_DIRECTORY_NOT_WRITABLE)
    }

    @Test
    fun `upload copies multiple buffers through output stream and closes all resources`() = runTest {
        val bytes = ByteArray(70_003) { (it % 251).toByte() }
        val fixture = UploadFixture(bytes.size.toLong(), java.io.ByteArrayInputStream(bytes))
        val received = java.io.ByteArrayOutputStream()
        val lengths = mutableListOf<Int>()
        org.mockito.kotlin.doAnswer { call ->
            assertThat(call.getArgument<Int>(1)).isEqualTo(0)
            val length = call.getArgument<Int>(2)
            received.write(call.getArgument<ByteArray>(0), 0, length)
            lengths += length
            null
        }.`when`(fixture.output).write(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )

        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Success(bytes.size.toLong()))
        assertThat(received.toByteArray()).isEqualTo(bytes)
        assertThat(lengths.size).isGreaterThan(1)
        assertThat(lengths.last()).isLessThan(lengths.first())
        fixture.verifyExclusiveOpen()
        fixture.verifyClosed()
    }

    @Test
    fun `upload accepts an empty source without writing data`() = runTest {
        val fixture = UploadFixture(0)
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Success(0L))
        fixture.verifyExclusiveOpen()
        org.mockito.kotlin.verify(fixture.output, org.mockito.kotlin.never()).write(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
        fixture.verifyClosed()
    }

    @Test
    fun `upload rejects negative size before opening source or connecting`() = runTest {
        val fixture = UploadFixture(-1)
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.INVALID_CONFIGURATION))
        assertThat(fixture.sourceOpened).isFalse()
        org.mockito.kotlin.verifyNoInteractions(fixture.ssh)
    }

    @Test
    fun `upload maps source open failure to UNKNOWN without creating a remote file`() = runTest {
        val fixture = UploadFixture(1, openError = java.io.IOException("Source unavailable"))
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        org.mockito.kotlin.verifyNoInteractions(fixture.ssh, fixture.sftp, fixture.remote)
    }

    @Test
    fun `upload closes resources after a local read failure following data`() = runTest {
        val input = object : java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            private var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw java.net.SocketException("Local provider failed")
                first = false
                return super.read(buffer, offset, length)
            }
        }
        val fixture = UploadFixture(6, input)
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        org.mockito.kotlin.verify(fixture.output).write(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(0),
            org.mockito.kotlin.eq(3)
        )
        fixture.verifyClosed()
    }

    @Test
    fun `upload refuses an existing remote target without writing or deleting`() = runTest {
        val fixture = UploadFixture(1, java.io.ByteArrayInputStream(byteArrayOf(1)))
        org.mockito.kotlin.whenever(fixture.sftp.open(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any()))
            .thenThrow(net.schmizz.sshj.sftp.SFTPException("Already exists"))
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifyExclusiveOpen()
        org.mockito.kotlin.verifyNoInteractions(fixture.remote)
        fixture.verifyClosed(remoteOpened = false)
    }

    @Test
    fun `upload maps remote write failure to UNKNOWN and closes resources`() = runTest {
        val fixture = UploadFixture(1, java.io.ByteArrayInputStream(byteArrayOf(1)))
        doThrow(net.schmizz.sshj.sftp.SFTPException("Write failed"))
            .`when`(fixture.output).write(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any()
            )
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifyClosed()
    }

    @Test
    fun `upload preserves a remote write timeout`() = runTest {
        val fixture = UploadFixture(1, java.io.ByteArrayInputStream(byteArrayOf(1)))
        doThrow(java.net.SocketTimeoutException("Write timeout"))
            .`when`(fixture.output).write(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any()
            )
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.TIMEOUT))
        fixture.verifyClosed()
    }

    @Test
    fun `upload fails when EOF precedes the declared size`() = runTest {
        val fixture = UploadFixture(4, java.io.ByteArrayInputStream(byteArrayOf(1, 2)))
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifyClosed()
    }

    @Test
    fun `upload reads through EOF but fails when source exceeds declared size`() = runTest {
        val fixture = UploadFixture(1, java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        org.mockito.kotlin.verify(fixture.output).write(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.eq(0),
            org.mockito.kotlin.eq(3)
        )
        fixture.verifyClosed()
    }

    @Test
    fun `upload propagates cancellation and closes opened resources`() = runTest {
        val fixture = UploadFixture(1, java.io.ByteArrayInputStream(byteArrayOf(1)))
        val cancellation = kotlinx.coroutines.CancellationException("Cancelled")
        doThrow(cancellation).`when`(fixture.output).write(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any()
        )
        var caught: kotlinx.coroutines.CancellationException? = null
        try {
            fixture.upload()
            throw AssertionError("Cancellation must propagate")
        } catch (error: kotlinx.coroutines.CancellationException) {
            caught = error
        }
        assertThat(caught).isNotNull()
        assertThat(caught!!.message).isEqualTo(cancellation.message)
        fixture.verifyClosed()
    }

    @Test
    fun `upload cannot succeed when remote close fails`() = runTest {
        val fixture = UploadFixture(0)
        doThrow(net.schmizz.sshj.sftp.SFTPException("Close failed")).`when`(fixture.remote).close()
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifyClosed()
    }

    @Test
    fun `upload cannot succeed when source close fails`() = runTest {
        val fixture = UploadFixture(0, object : java.io.ByteArrayInputStream(byteArrayOf()) {
            override fun close() { throw java.io.IOException("Close failed") }
        })
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifyClosed()
    }

    @Test
    fun `upload cannot succeed when SFTP or SSH close fails`() = runTest {
        for (closeSftp in listOf(true, false)) {
            val fixture = UploadFixture(0)
            if (closeSftp) {
                doThrow(java.io.IOException("Close failed")).`when`(fixture.sftp).close()
            } else {
                doThrow(java.io.IOException("Close failed")).`when`(fixture.ssh).close()
            }
            assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
            fixture.verifyClosed()
        }
    }

    @Test
    fun `upload preserves SFTP opening timeout and closes source and SSH`() = runTest {
        val fixture = UploadFixture(0)
        org.mockito.kotlin.whenever(fixture.ssh.newSFTPClient())
            .thenThrow(java.net.SocketTimeoutException("SFTP opening timeout"))
        assertThat(fixture.upload()).isEqualTo(NasSftpResult.Failure(NasSftpError.TIMEOUT))
        assertThat(fixture.sourceCloseCount).isEqualTo(1)
        org.mockito.kotlin.verify(fixture.ssh).close()
        org.mockito.kotlin.verifyNoInteractions(fixture.sftp, fixture.remote)
    }

    @Test
    fun `upload and remote size reuse connection without SSHJ erasing its password`() = runTest {
        val originalPassword = charArrayOf('t', 'e', 's', 't')
        val expectedPassword = originalPassword.copyOf()
        val connection = NasSftpConnection(
            NasTransferSettings(host = "nas.example", username = "streamvault", remoteDirectory = "/films"),
            originalPassword,
            null
        )
        val uploadSsh: SSHClient = mock()
        val sizeSsh: SSHClient = mock()
        val uploadSftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val sizeSftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val remote: net.schmizz.sshj.sftp.RemoteFile = mock()
        val attributes: net.schmizz.sshj.sftp.FileAttributes = mock()
        val source: com.streamvault.domain.repository.NasTransferSource = mock()
        val authenticationCopies = mutableListOf<CharArray>()

        org.mockito.kotlin.whenever(uploadSsh.newSFTPClient()).thenReturn(uploadSftp)
        org.mockito.kotlin.whenever(sizeSsh.newSFTPClient()).thenReturn(sizeSftp)
        org.mockito.kotlin.whenever(uploadSftp.open(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any()))
            .thenReturn(remote)
        org.mockito.kotlin.whenever(sizeSftp.statExistence("/films/test-file.bin")).thenReturn(attributes)
        org.mockito.kotlin.whenever(attributes.size).thenReturn(0L)
        org.mockito.kotlin.whenever(attributes.type).thenReturn(net.schmizz.sshj.sftp.FileMode.Type.REGULAR)
        org.mockito.kotlin.whenever(source.sizeBytes).thenReturn(0L)
        org.mockito.kotlin.whenever(source.openInputStream()).thenReturn(java.io.ByteArrayInputStream(byteArrayOf()))
        listOf(uploadSsh, sizeSsh).forEachIndexed { index, ssh ->
            org.mockito.kotlin.doAnswer { invocation ->
                val received = invocation.getArgument<CharArray>(1)
                // Assert booleans only: a failure must never print credential contents.
                assertThat(received === originalPassword).isFalse()
                assertThat(received.contentEquals(expectedPassword)).isTrue()
                assertThat(authenticationCopies.any { it === received }).isFalse()
                authenticationCopies += received
                // First mimic SSHJ's blankOut; then leave a mutation for our finally to erase.
                received.fill(if (index == 0) '\u0000' else 'x')
                null
            }.`when`(ssh).authPassword(org.mockito.kotlin.eq("streamvault"), org.mockito.kotlin.any<CharArray>())
        }
        val sessions = listOf(uploadSsh, sizeSsh).iterator()
        val client = SshjNasSftpClient { sessions.next() }

        assertThat(client.upload(connection, source, "/films/test-file.bin")).isEqualTo(NasSftpResult.Success(0L))
        assertThat(authenticationCopies.size).isEqualTo(1)
        assertThat(originalPassword.contentEquals(expectedPassword)).isTrue()
        assertThat(authenticationCopies.single().all { it == '\u0000' }).isTrue()

        assertThat(client.getRemoteSize(connection, "/films/test-file.bin")).isEqualTo(NasSftpResult.Success(0L))
        assertThat(authenticationCopies.size).isEqualTo(2)
        assertThat(connection.password === originalPassword).isTrue()
        assertThat(originalPassword.contentEquals(expectedPassword)).isTrue()
        assertThat(authenticationCopies.all { copy -> copy.all { it == '\u0000' } }).isTrue()
    }

    @Test
    fun `rename uses only the standard overload with exact paths`() = runTest {
        val fixture = RenameFixture()
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Success(Unit))
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename server refusal fails without fallback`() = runTest {
        val fixture = RenameFixture(net.schmizz.sshj.sftp.SFTPException("Permission denied"))
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename missing source maps to UNKNOWN rather than directory not found`() = runTest {
        val fixture = RenameFixture(net.schmizz.sshj.sftp.SFTPException(
            net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE, "Source absent"
        ))
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename existing destination fails without overwrite or second attempt`() = runTest {
        val fixture = RenameFixture(net.schmizz.sshj.sftp.SFTPException("Destination exists"))
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename generic exception fails and closes resources`() = runTest {
        val fixture = RenameFixture(java.io.IOException("Rename failed"))
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename propagates cancellation and closes resources`() = runTest {
        val fixture = RenameFixture(kotlinx.coroutines.CancellationException("Rename cancelled"))
        try {
            fixture.rename()
            throw AssertionError("Cancellation must propagate")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            assertThat(cancelled.message).isEqualTo("Rename cancelled")
        }
        fixture.verifySingleStandardRenameAndClosure()
    }

    @Test
    fun `rename rejects blank paths and invalid configuration before connecting`() = runTest {
        val fixture = RenameFixture()
        val invalidCalls = listOf(
            Triple(fixture.connection, " ", "/films/destination.bin"),
            Triple(fixture.connection, "/films/source.bin", ""),
            Triple(NasSftpConnection(fixture.connection.settings.copy(host = ""), charArrayOf('t'), null), "/films/source.bin", "/films/destination.bin"),
            Triple(NasSftpConnection(fixture.connection.settings, charArrayOf(), null), "/films/source.bin", "/films/destination.bin")
        )
        for ((connection, source, destination) in invalidCalls) {
            assertThat(fixture.client.rename(connection, source, destination))
                .isEqualTo(NasSftpResult.Failure(NasSftpError.INVALID_CONFIGURATION))
        }
        org.mockito.kotlin.verifyNoInteractions(fixture.ssh, fixture.sftp)
    }

    @Test
    fun `rename preserves connection errors without attempting an operation`() = runTest {
        val fixture = RenameFixture()
        doThrow(java.net.ConnectException("Connection refused"))
            .`when`(fixture.ssh).connect("nas.example", 22)
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.CONNECTION_REFUSED))
        org.mockito.kotlin.verifyNoInteractions(fixture.sftp)
        org.mockito.kotlin.verify(fixture.ssh).close()
    }

    @Test
    fun `rename close failure prevents success`() = runTest {
        val fixture = RenameFixture()
        doThrow(java.io.IOException("Close failed")).`when`(fixture.sftp).close()
        assertThat(fixture.rename()).isEqualTo(NasSftpResult.Failure(NasSftpError.UNKNOWN))
        fixture.verifySingleStandardRenameAndClosure()
    }

    private class RenameFixture(error: Exception? = null) {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val connection = NasSftpConnection(
            NasTransferSettings(host = "nas.example", username = "streamvault", remoteDirectory = "/films"),
            charArrayOf('t', 'e', 's', 't'),
            null
        )
        val client = SshjNasSftpClient { ssh }

        init {
            org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
            if (error != null) {
                doThrow(error).`when`(sftp).rename("/films/source.bin", "/films/destination.bin")
            }
        }

        suspend fun rename(): NasSftpResult<Unit> =
            client.rename(connection, "/films/source.bin", "/films/destination.bin")

        fun verifySingleStandardRenameAndClosure() {
            org.mockito.kotlin.verify(sftp).rename("/films/source.bin", "/films/destination.bin")
            org.mockito.kotlin.verify(sftp).close()
            // Excludes flags overload, stat, deletion, and any second rename attempt.
            org.mockito.kotlin.verifyNoMoreInteractions(sftp)
            org.mockito.kotlin.verify(ssh).close()
        }
    }

    private class UploadFixture(
        size: Long,
        input: java.io.InputStream = java.io.ByteArrayInputStream(byteArrayOf()),
        private val openError: Exception? = null
    ) {
        val ssh: SSHClient = mock()
        val sftp: net.schmizz.sshj.sftp.SFTPClient = mock()
        val remote: net.schmizz.sshj.sftp.RemoteFile = mock()
        val output: java.io.OutputStream = mock()
        var sourceOpened = false
        var sourceCloseCount = 0
            private set
        private val stream = object : java.io.FilterInputStream(input) {
            override fun close() {
                sourceCloseCount++
                super.close()
            }
        }
        private val source = object : com.streamvault.domain.repository.NasTransferSource {
            override val sizeBytes = size
            override val displayName: String get() = error("Upload must not read displayName")
            override val uriString: String get() = error("Upload must not read uriString")
            override suspend fun openInputStream(): java.io.InputStream {
                sourceOpened = true
                openError?.let { throw it }
                return stream
            }
            override suspend fun openInputStreamAt(offsetBytes: Long): java.io.InputStream? =
                error("Upload must not seek")
            override suspend fun deleteAfterValidatedTransfer(): Boolean =
                error("Upload must preserve the local source")
        }
        init {
            org.mockito.kotlin.whenever(ssh.newSFTPClient()).thenReturn(sftp)
            org.mockito.kotlin.whenever(sftp.open(org.mockito.kotlin.any<String>(), org.mockito.kotlin.any())).thenReturn(remote)
        }
        suspend fun upload(): NasSftpResult<Long> = SshjNasSftpClient({ ssh }) { output }.upload(
            NasSftpConnection(
                NasTransferSettings(host = "nas.example", username = "streamvault", remoteDirectory = "/films"),
                "secret".toCharArray(),
                null
            ),
            source,
            "/films/test-file.bin"
        )
        fun verifyExclusiveOpen() {
            org.mockito.kotlin.verify(sftp).open(
                "/films/test-file.bin",
                java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.EXCL, net.schmizz.sshj.sftp.OpenMode.WRITE)
            )
        }
        fun verifyClosed(remoteOpened: Boolean = true) {
            assertThat(sourceCloseCount).isEqualTo(1)
            if (remoteOpened) {
                org.mockito.kotlin.verify(output).close()
                org.mockito.kotlin.verify(remote).close()
            } else {
                org.mockito.kotlin.verify(output, org.mockito.kotlin.never()).close()
            }
            org.mockito.kotlin.verify(sftp).close()
            org.mockito.kotlin.verify(ssh).close()
            org.mockito.kotlin.verify(sftp, org.mockito.kotlin.never()).rm(org.mockito.kotlin.any())
            org.mockito.kotlin.verify(sftp, org.mockito.kotlin.never()).statExistence(org.mockito.kotlin.any())
        }
    }
}
