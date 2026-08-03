package com.yugahashimoto.andcode.startup

import android.content.Intent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutoStartPolicyTest {
    @Test
    fun `restores an installed local runtime after a process losing event`() {
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
            ),
        )
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = "local-android",
            ),
        )
    }

    @Test
    fun `does not start when onboarding is incomplete or runtime is not installed`() {
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = false,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
            ),
        )
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.NotInstalled,
                selectedRuntimeId = null,
            ),
        )
    }

    @Test
    fun `does not take over an explicitly selected remote runtime`() {
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = "remote-runtime",
            ),
        )
    }

    @Test
    fun `recognizes reboot and package replacement as runtime recovery triggers`() {
        assertTrue(isRuntimeAutoStartBroadcast(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isRuntimeAutoStartBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isRuntimeAutoStartBroadcast("com.yugahashimoto.andcode.RUN_SCHEDULE"))
    }
}
