package com.streamvault.data.nas

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.NasTransferSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NasTransferSettingsRepositoryImplTest {
    @Test
    fun `settings write persists non sensitive fields and clears stale trust when endpoint changes`() = runTest {
        val preferences: PreferencesRepository = mock()
        whenever(preferences.nasTransferSettings).thenReturn(
            MutableStateFlow(
                NasTransferSettings(
                    host = "old-nas",
                    username = "user",
                    remoteDirectory = "/old"
                )
            )
        )
        val repository = NasTransferSettingsRepositoryImpl(preferences)

        repository.updateSettings(
            NasTransferSettings(
                enabled = true,
                host = "new-nas",
                port = 2222,
                username = "new-user",
                remoteDirectory = "/Films"
            )
        )

        val captured = argumentCaptor<NasTransferSettings>()
        verify(preferences).setNasTransferSettings(captured.capture())
        assertThat(captured.firstValue.host).isEqualTo("new-nas")
        assertThat(captured.firstValue.remoteDirectory).isEqualTo("/Films")
        assertThat(captured.firstValue.trustedHostKey).isNull()
    }
}
