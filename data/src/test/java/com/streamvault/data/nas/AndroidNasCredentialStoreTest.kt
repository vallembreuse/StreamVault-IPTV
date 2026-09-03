package com.streamvault.data.nas

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.security.CredentialCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidNasCredentialStoreTest {
    private lateinit var context: Context

    private val crypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = "ciphertext:opaque-test-payload"
        override fun decryptIfNeeded(value: String): String = "secret-for-test"
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        credentialFile().delete()
    }

    @After
    fun tearDown() {
        credentialFile().delete()
    }

    @Test
    fun `password is stored separately as ciphertext and is never exposed by settings storage`() = runTest {
        val store = AndroidNasCredentialStore(context, crypto)
        val password = "secret-for-test".toCharArray()

        store.savePassword(password)

        assertThat(store.observePasswordConfigured().first()).isTrue()
        assertThat(store.readPassword()!!.concatToString()).isEqualTo("secret-for-test")
        val rawFile = credentialFile().readBytes().decodeToString()
        assertThat(rawFile).contains("ciphertext:opaque-test-payload")
        assertThat(rawFile).doesNotContain("secret-for-test")
    }

    @Test
    fun `clearing credential removes configured state`() = runTest {
        val store = AndroidNasCredentialStore(context, crypto)
        store.savePassword("secret-for-test".toCharArray())

        store.clearPassword()

        assertThat(store.observePasswordConfigured().first()).isFalse()
        assertThat(store.readPassword()).isNull()
    }

    private fun credentialFile() = context.preferencesDataStoreFile("nas_transfer_credentials")
}
