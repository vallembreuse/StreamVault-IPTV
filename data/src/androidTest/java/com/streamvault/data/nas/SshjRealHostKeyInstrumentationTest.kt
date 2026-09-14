package com.streamvault.data.nas

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.PublicKey
import java.security.Security

@RunWith(AndroidJUnit4::class)
class SshjRealHostKeyInstrumentationTest {

    @Test
    fun reachesRealNasHostKeyVerifierWithoutAuthentication() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)

        if (provider?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        var verifierCalled = false
        var algorithm: String? = null
        var fingerprint: String? = null

        val ssh = SSHClient().apply {
            connectTimeout = 15_000
            timeout = 30_000

            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(
                    hostname: String,
                    port: Int,
                    key: PublicKey
                ): Boolean {
                    verifierCalled = true
                    algorithm = key.algorithm
                    fingerprint = SecurityUtils.getFingerprint(key)

                    // Deliberately reject the key:
                    // we only want to test the pre-authentication handshake.
                    return false
                }

                override fun findExistingAlgorithms(
                    hostname: String,
                    port: Int
                ): List<String> = emptyList()
            })
        }

        try {
            ssh.connect("192.168.1.26", 22)
        } catch (t: Throwable) {
            assertTrue(
                "HostKeyVerifier was never called. Exception=${t.javaClass.name}: ${t.message}",
                verifierCalled
            )

            assertTrue(
                "Unexpected host-key algorithm: $algorithm",
                !algorithm.isNullOrBlank()
            )

            assertNotNull(
                "Fingerprint was not computed for the real NAS host key",
                fingerprint
            )

            return
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }

        throw AssertionError("SSH connection unexpectedly continued after rejecting the host key")
    }
}
