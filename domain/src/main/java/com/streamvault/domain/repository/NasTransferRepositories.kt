package com.streamvault.domain.repository

import com.streamvault.domain.model.NasTransfer
import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.model.NasTransferSettings
import kotlinx.coroutines.flow.Flow

/** Stores non-secret NAS settings only. */
interface NasTransferSettingsRepository {
    fun observeSettings(): Flow<NasTransferSettings>

    suspend fun updateSettings(settings: NasTransferSettings)

    suspend fun trustHostKey(trust: NasHostKeyTrust)
}

/** Separates the SSH secret from Room and normal NAS settings. */
interface NasCredentialStore {
    fun observePasswordConfigured(): Flow<Boolean>

    suspend fun readPassword(): CharArray?

    suspend fun savePassword(password: CharArray)

    suspend fun clearPassword()
}

/** Persistent NAS ledger. Callers supply identity, timestamps and non-sensitive business errors. */
interface NasTransferRepository {
    fun observeRecoverableQueue(): Flow<List<NasTransfer>>
    suspend fun getById(id: String): NasTransfer?
    suspend fun insert(transfer: NasTransfer)

    /** Returns false for a missing row or rejected business change; storage errors propagate. */
    suspend fun update(transfer: NasTransfer): Boolean
}
