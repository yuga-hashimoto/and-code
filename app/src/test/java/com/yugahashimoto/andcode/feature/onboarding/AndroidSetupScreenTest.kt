package com.yugahashimoto.andcode.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSetupScreenTest {
    @Test
    fun `minimal install is skipped only when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = false, installFullDevelopmentTools = false))
        assertFalse(shouldStartRuntimeInstall(installComplete = true, installFullDevelopmentTools = false))
    }

    @Test
    fun `full toolchain option runs even when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = true, installFullDevelopmentTools = true))
    }

    @Test
    fun `full toolchain is not started again when it is already installed`() {
        assertFalse(
            shouldStartRuntimeInstall(
                installComplete = true,
                installFullDevelopmentTools = true,
                fullDevelopmentToolsInstalled = true,
            ),
        )
    }
}
