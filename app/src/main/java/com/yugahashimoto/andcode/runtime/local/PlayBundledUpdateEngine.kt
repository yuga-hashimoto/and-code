package com.yugahashimoto.andcode.runtime.local

/** Play updates OpenCode only to the version included in this app, never from the network. */
class PlayBundledUpdateEngine(
    private val delegate: LocalRuntimeUpdateEngine,
    private val installedAbi: String,
    private val bundledRelease: (String) -> LocalRuntimeRelease,
) : LocalRuntimeUpdateEngine by delegate {
    override suspend fun check(
        currentVersion: String,
        abi: String,
    ): LocalRuntimeUpdateCheck {
        require(abi == installedAbi) { "Unexpected ABI for bundled OpenCode update" }
        val release = bundledRelease(abi)
        return if (compareRuntimeVersions(release.version, currentVersion) > 0) {
            LocalRuntimeUpdateCheck.Available(currentVersion, release)
        } else {
            LocalRuntimeUpdateCheck.UpToDate(currentVersion, release.version)
        }
    }

    override suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate {
        require(release == bundledRelease(installedAbi)) { "OpenCode update is not the bundled release" }
        return delegate.prepare(release, onProgress)
    }
}
