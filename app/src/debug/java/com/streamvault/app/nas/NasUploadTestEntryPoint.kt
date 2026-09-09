package com.streamvault.app.nas

import com.streamvault.domain.repository.NasCredentialStore
import com.streamvault.domain.repository.NasSftpClient
import com.streamvault.domain.repository.NasTransferSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Access to the existing application singletons for the opt-in physical upload test. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NasUploadTestEntryPoint {
    fun nasSftpClient(): NasSftpClient
    fun nasTransferSettingsRepository(): NasTransferSettingsRepository
    fun nasCredentialStore(): NasCredentialStore
}
