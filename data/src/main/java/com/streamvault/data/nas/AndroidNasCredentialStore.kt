package com.streamvault.data.nas

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.repository.NasCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated credential store. Its one persisted value is AES-GCM ciphertext whose key lives in
 * Android Keystore; no plain credential is ever written to Room or normal preferences.
 */
@Singleton
class AndroidNasCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialCrypto: CredentialCrypto
) : NasCredentialStore {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) }
    )

    override fun observePasswordConfigured(): Flow<Boolean> = store.data.map { preferences ->
        !preferences[PASSWORD_CIPHERTEXT].isNullOrBlank()
    }

    override suspend fun readPassword(): CharArray? = store.data.first()[PASSWORD_CIPHERTEXT]
        ?.takeIf { it.isNotBlank() }
        ?.let(credentialCrypto::decryptIfNeeded)
        ?.toCharArray()

    override suspend fun savePassword(password: CharArray) {
        if (password.isEmpty()) {
            clearPassword()
            return
        }
        val passwordCopy = password.copyOf()
        try {
            val plainText = passwordCopy.concatToString()
            val ciphertext = credentialCrypto.encryptIfNeeded(plainText)
            check(ciphertext != plainText) { "NAS credential encryption did not produce ciphertext" }
            store.edit { preferences -> preferences[PASSWORD_CIPHERTEXT] = ciphertext }
        } finally {
            passwordCopy.fill('\u0000')
        }
    }

    override suspend fun clearPassword() {
        store.edit { preferences -> preferences.remove(PASSWORD_CIPHERTEXT) }
    }

    private companion object {
        const val DATASTORE_NAME = "nas_transfer_credentials"
        val PASSWORD_CIPHERTEXT = stringPreferencesKey("ssh_password_ciphertext")
    }
}
