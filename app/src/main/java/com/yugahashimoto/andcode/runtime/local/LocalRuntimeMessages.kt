package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import com.yugahashimoto.andcode.R

/**
 * User-visible text produced by the local runtime layer.
 *
 * These messages reach the screen through [com.yugahashimoto.andcode.runtime.LocalRuntimeStatus.Broken]
 * and command results, so they have to be localised like any other UI string. The layer stays
 * testable by depending on this interface instead of on a [Context].
 */
interface LocalRuntimeMessages {
    val installFailed: String
    val reinstallFailed: String
    val startFailed: String
    val stopFailed: String
    val deleteFailed: String
    val deleteIncomplete: String
    val missingFiles: String
    val updateCheckFailed: String
    val updatePrepareFailed: String
    val rollbackCheckFailed: String
    val restoreFailed: String
    val startAfterUpdateFailed: String
    val startAfterRollbackFailed: String
    val notInstalled: String
    val commandTimedOut: String
    val runtimeStopped: String
    val runtimeConnecting: String
    val localRuntimeUnhealthy: String
    val localRuntimeConnectionFailed: String
    val adbRequiresAndroid11: String
    val adbServiceUnavailable: String
    val adbDiscoveryFailed: String
    val adbPairFailed: String
    val adbConnectFailed: String
    val diagnosticsToolAvailable: String
    val diagnosticsToolCheckFailed: String
    val diagnosticsToolNotInstalled: String
    val diagnosticsCaCertificates: String

    fun unsupportedAbi(abi: String): String

    fun preparingUpdate(): String

    fun rollingBackTo(version: String): String

    fun insufficientFreeSpace(
        requiredBytes: Long,
        availableBytes: Long,
    ): String

    fun downloadingOpenCode(version: String): String

    fun extractingUpdate(): String

    fun verifyingUpdate(): String

    fun updateCandidateReady(): String

    fun diagnosticsToolExitCode(code: Int): String

    fun adbDiscoveryStartFailed(code: Int): String

    fun restoredButCannotStart(
        version: String,
        reason: String,
    ): String

    /**
     * English fallbacks, used by unit tests and by any construction path that has no [Context].
     * Production wiring always injects [AndroidLocalRuntimeMessages].
     */
    companion object Default : LocalRuntimeMessages {
        override val installFailed = "Could not install the local runtime"
        override val reinstallFailed = "Could not reinstall the local runtime"
        override val startFailed = "Could not start local OpenCode"
        override val stopFailed = "Could not stop local OpenCode"
        override val deleteFailed = "Could not delete the local runtime"
        override val deleteIncomplete = "The local runtime could not be fully deleted"
        override val missingFiles = "Local runtime files are missing"
        override val updateCheckFailed = "Could not check for OpenCode updates"
        override val updatePrepareFailed = "Could not prepare the OpenCode update"
        override val rollbackCheckFailed = "Could not determine a version to revert to"
        override val restoreFailed = "Could not restore the OpenCode runtime"
        override val startAfterUpdateFailed = "Could not start OpenCode after the update"
        override val startAfterRollbackFailed = "Could not start OpenCode after reverting"
        override val notInstalled = "Not installed"
        override val commandTimedOut = "The check timed out"
        override val runtimeStopped = "The local runtime is stopped"
        override val runtimeConnecting = "Connecting to the local runtime"
        override val localRuntimeUnhealthy = "The local OpenCode runtime is unhealthy"
        override val localRuntimeConnectionFailed = "Could not connect to local OpenCode"
        override val adbRequiresAndroid11 = "Requires Android 11 or later"
        override val adbServiceUnavailable = "Wireless debugging service is unavailable"
        override val adbDiscoveryFailed = "Could not start discovery"
        override val adbPairFailed = "Pairing failed"
        override val adbConnectFailed = "Connection failed"
        override val diagnosticsToolAvailable = "Available"
        override val diagnosticsToolCheckFailed = "Could not check"
        override val diagnosticsToolNotInstalled = "Not installed"
        override val diagnosticsCaCertificates = "CA certificates"

        override fun unsupportedAbi(abi: String) = "Unsupported ABI: $abi"

        override fun preparingUpdate() = "Preparing the update"

        override fun rollingBackTo(version: String) = "Reverting to OpenCode $version"

        override fun insufficientFreeSpace(
            requiredBytes: Long,
            availableBytes: Long,
        ) = "Not enough free space for the update: $requiredBytes bytes required, $availableBytes bytes available"

        override fun downloadingOpenCode(version: String) = "Downloading OpenCode $version"

        override fun extractingUpdate() = "Extracting the update"

        override fun verifyingUpdate() = "Verifying the update candidate"

        override fun updateCandidateReady() = "Update candidate is ready"

        override fun diagnosticsToolExitCode(code: Int) = "Exit code $code"

        override fun adbDiscoveryStartFailed(code: Int) = "Could not start discovery (code=$code)"

        override fun restoredButCannotStart(
            version: String,
            reason: String,
        ) = "Restored OpenCode $version but it will not start: $reason"
    }
}

class AndroidLocalRuntimeMessages(private val context: Context) : LocalRuntimeMessages {
    override val installFailed get() = context.getString(R.string.runtime_error_install_failed)
    override val reinstallFailed get() = context.getString(R.string.runtime_error_reinstall_failed)
    override val startFailed get() = context.getString(R.string.runtime_error_start_failed)
    override val stopFailed get() = context.getString(R.string.runtime_error_stop_failed)
    override val deleteFailed get() = context.getString(R.string.runtime_error_delete_failed)
    override val deleteIncomplete get() = context.getString(R.string.runtime_error_delete_incomplete)
    override val missingFiles get() = context.getString(R.string.runtime_error_missing_files)
    override val updateCheckFailed get() = context.getString(R.string.runtime_error_update_check_failed)
    override val updatePrepareFailed get() = context.getString(R.string.runtime_error_update_prepare_failed)
    override val rollbackCheckFailed get() = context.getString(R.string.runtime_error_rollback_check_failed)
    override val restoreFailed get() = context.getString(R.string.runtime_error_restore_failed)
    override val startAfterUpdateFailed get() = context.getString(R.string.runtime_error_start_after_update_failed)
    override val startAfterRollbackFailed get() = context.getString(R.string.runtime_error_start_after_rollback_failed)
    override val notInstalled get() = context.getString(R.string.runtime_status_not_installed)
    override val commandTimedOut get() = context.getString(R.string.runtime_error_command_timed_out)
    override val runtimeStopped get() = context.getString(R.string.runtime_status_stopped_reason)
    override val runtimeConnecting get() = context.getString(R.string.runtime_status_connecting_reason)
    override val localRuntimeUnhealthy get() = context.getString(R.string.runtime_connection_unhealthy)
    override val localRuntimeConnectionFailed get() = context.getString(R.string.runtime_connection_failed)
    override val adbRequiresAndroid11 get() = context.getString(R.string.adb_requires_android_11)
    override val adbServiceUnavailable get() = context.getString(R.string.adb_service_unavailable)
    override val adbDiscoveryFailed get() = context.getString(R.string.adb_discovery_failed)
    override val adbPairFailed get() = context.getString(R.string.adb_pair_failed)
    override val adbConnectFailed get() = context.getString(R.string.adb_connect_failed)
    override val diagnosticsToolAvailable get() = context.getString(R.string.diagnostics_tool_available)
    override val diagnosticsToolCheckFailed get() = context.getString(R.string.diagnostics_tool_check_failed)
    override val diagnosticsToolNotInstalled get() = context.getString(R.string.diagnostics_tool_not_installed)
    override val diagnosticsCaCertificates get() = context.getString(R.string.diagnostics_tool_ca_certificates)

    override fun unsupportedAbi(abi: String): String = context.getString(R.string.unsupported_abi, abi)

    override fun preparingUpdate(): String = context.getString(R.string.runtime_step_preparing_update)

    override fun rollingBackTo(version: String): String = context.getString(R.string.runtime_step_rolling_back_to, version)

    override fun insufficientFreeSpace(
        requiredBytes: Long,
        availableBytes: Long,
    ): String = context.getString(R.string.runtime_update_insufficient_space, requiredBytes, availableBytes)

    override fun downloadingOpenCode(version: String): String = context.getString(R.string.runtime_step_downloading_opencode, version)

    override fun extractingUpdate(): String = context.getString(R.string.runtime_step_extracting_update)

    override fun verifyingUpdate(): String = context.getString(R.string.runtime_step_verifying_update)

    override fun updateCandidateReady(): String = context.getString(R.string.runtime_step_update_candidate_ready)

    override fun diagnosticsToolExitCode(code: Int): String = context.getString(R.string.diagnostics_tool_exit_code, code)

    override fun adbDiscoveryStartFailed(code: Int): String = context.getString(R.string.adb_discovery_start_failed, code)

    override fun restoredButCannotStart(
        version: String,
        reason: String,
    ): String = context.getString(R.string.runtime_error_restored_but_cannot_start, version, reason)
}
