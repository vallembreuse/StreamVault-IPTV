package com.streamvault.data.nas

import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasTransferSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NasTransferSettingsRepositoryImpl @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : NasTransferSettingsRepository {
    override fun observeSettings(): Flow<NasTransferSettings> = preferencesRepository.nasTransferSettings

    override suspend fun updateSettings(settings: NasTransferSettings) {
        val current = observeSettings().first()
        val normalized = settings.normalized()
        // A trust pin belongs to one network endpoint only. Editing the endpoint requires an
        // explicit new confirmation instead of accepting a key meant for the former server.
        val trust = normalized.trustedHostKey?.takeIf {
            it.host == normalized.host && it.port == normalized.port &&
                current.host == normalized.host && current.port == normalized.port
        }
        preferencesRepository.setNasTransferSettings(normalized.copy(trustedHostKey = trust))
    }

    override suspend fun trustHostKey(trust: NasHostKeyTrust) {
        val current = observeSettings().first().normalized()
        if (current.host == trust.host && current.port == trust.port) {
            preferencesRepository.setNasTransferSettings(current.copy(trustedHostKey = trust))
        }
    }
}
