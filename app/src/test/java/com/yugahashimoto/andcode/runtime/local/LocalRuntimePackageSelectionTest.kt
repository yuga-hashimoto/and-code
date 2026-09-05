package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimePackageSelectionTest {
    @Test
    fun `Alpine default excludes heavy development packages`() {
        assertTrue(LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES.containsAll(listOf("git", "android-tools", "python3")))
        assertFalse(LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES.any { it in listOf("openjdk17", "gradle", "nodejs", "gcc", "go") })
        assertTrue(LocalRuntimeInstaller.OPTIONAL_DEVELOPMENT_PACKAGES.containsAll(listOf("openjdk17", "gradle", "nodejs", "gcc", "go")))
        assertTrue(
            LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES
                .intersect(LocalRuntimeInstaller.OPTIONAL_DEVELOPMENT_PACKAGES.toSet())
                .isEmpty(),
        )
    }

    @Test
    fun `Debian default excludes heavy development packages`() {
        assertTrue(DebianRootfsInstaller.REQUIRED_RUNTIME_PACKAGES.containsAll(listOf("git", "adb", "python3")))
        assertFalse(
            DebianRootfsInstaller.REQUIRED_RUNTIME_PACKAGES.any {
                it in listOf("openjdk-17-jdk-headless", "gradle", "nodejs", "gcc", "golang-go")
            },
        )
        assertTrue(
            DebianRootfsInstaller.OPTIONAL_DEVELOPMENT_PACKAGES.containsAll(
                listOf("openjdk-17-jdk-headless", "gradle", "nodejs", "gcc", "golang-go"),
            ),
        )
        assertTrue(
            DebianRootfsInstaller.REQUIRED_RUNTIME_PACKAGES
                .intersect(DebianRootfsInstaller.OPTIONAL_DEVELOPMENT_PACKAGES.toSet())
                .isEmpty(),
        )
    }
}
