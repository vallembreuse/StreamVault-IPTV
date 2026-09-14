package com.streamvault.sftpdiagnostic

import com.streamvault.data.nas.SshjNasSftpClient
import com.streamvault.domain.model.NasHostKeyTrust
import com.streamvault.domain.model.NasTransferSettings
import com.streamvault.domain.repository.NasSftpConnection
import com.streamvault.domain.repository.NasSftpResult
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Local-only SFTP validation entrypoint. No credential is stored in source or output. */
object Runner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 6) {
            "Expected: <host> <port> <username> <remoteDirectory> <algorithm> <fingerprint>"
        }
        val credential = System.getenv("SV_SFTP_CREDENTIAL")?.toCharArray() ?: charArrayOf()
        if (credential.isEmpty()) {
            println("RESULT=INVALID_INPUT reason=missing-credential")
            exitProcess(64)
        }

        val host = args[0]
        val port = args[1].toInt()
        val username = args[2]
        val remoteDirectory = args[3]
        val trust = NasHostKeyTrust(host, port, args[4], args[5])
        val settings = NasTransferSettings(
            enabled = true,
            host = host,
            port = port,
            username = username,
            remoteDirectory = remoteDirectory,
            trustedHostKey = trust
        )
        val connection = NasSftpConnection(settings, credential, trust)

        val result = try {
            runBlocking { SshjNasSftpClient().testConnection(connection) }
        } finally {
            credential.fill('\u0000')
        }

        when (result) {
            is NasSftpResult.Success -> {
                println("RESULT=SUCCESS directoryVerified=${result.value.directoryVerified} writeVerified=${result.value.writeVerified}")
                exitProcess(if (result.value.directoryVerified && result.value.writeVerified) 0 else 2)
            }
            is NasSftpResult.Failure -> {
                println("RESULT=FAILURE error=${result.error}")
                exitProcess(3)
            }
            is NasSftpResult.HostKeyConfirmationRequired -> {
                println("RESULT=HOST_KEY_CONFIRMATION_REQUIRED algorithm=${result.trust.algorithm} fingerprint=${result.trust.fingerprint}")
                exitProcess(4)
            }
        }
    }
}
