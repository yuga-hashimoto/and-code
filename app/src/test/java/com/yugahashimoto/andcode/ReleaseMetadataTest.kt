package com.yugahashimoto.andcode

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReleaseMetadataTest {
    @Test
    fun `android package metadata matches the release tag`() {
        val releaseVersion = repositoryRoot().resolve(".release-version").readText().trim().removePrefix("v")

        assertEquals(releaseVersion, BuildConfig.VERSION_NAME)
        assertEquals(46, BuildConfig.VERSION_CODE)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: error("Test working directory is unavailable")
        var directory = File(workingDirectory)
        while (true) {
            if (directory.resolve(".release-version").isFile) return directory
            directory = directory.parentFile ?: error("Could not locate .release-version from $workingDirectory")
        }
    }
}
