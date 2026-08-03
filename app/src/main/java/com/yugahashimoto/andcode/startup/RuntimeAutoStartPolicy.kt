package com.yugahashimoto.andcode.startup

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus

internal const val LOCAL_RUNTIME_ID = "local-android"

/**
 * Keeps the automatic recovery path scoped to a configured local runtime. An explicit remote
 * selection must never be replaced just because the app was updated or the device rebooted.
 */
internal fun shouldAutoStartLocalRuntime(
    onboardingCompleted: Boolean,
    localRuntimeStatus: LocalRuntimeStatus,
    selectedRuntimeId: String?,
): Boolean =
    onboardingCompleted &&
        localRuntimeStatus !is LocalRuntimeStatus.NotInstalled &&
        (selectedRuntimeId == null || selectedRuntimeId == LOCAL_RUNTIME_ID)
