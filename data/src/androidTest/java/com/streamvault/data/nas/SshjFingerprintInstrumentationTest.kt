package com.streamvault.data.nas

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec

@RunWith(AndroidJUnit4::class)
class SshjFingerprintInstrumentationTest {

    @Test
    fun computesEcdsaFingerprintWithAndroidBouncyCastle() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)

        if (provider?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        val publicKey = KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
            .public

        val fingerprint = SecurityUtils.getFingerprint(publicKey)

        assertTrue(
            "Unexpected SSH fingerprint: $fingerprint",
            fingerprint.matches(Regex("([0-9a-f]{2}:){15}[0-9a-f]{2}"))
        )
    }
}
