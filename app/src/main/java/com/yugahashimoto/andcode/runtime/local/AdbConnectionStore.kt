package com.yugahashimoto.andcode.runtime.local

/**
 * Remembers the wireless-debugging port the user last connected to, so the ADB link can be
 * restored automatically after the app or the Linux runtime restarts. The pairing keys already
 * survive restarts inside the rootfs (`/root/.android` is carried over on reinstall), so only the
 * port has to be persisted — a saved port can be re-connected without pairing again.
 */
interface AdbConnectionStore {
    fun saveConnectedPort(port: Int)

    fun loadConnectedPort(): Int?

    fun clearConnectedPort()
}

/** Volatile in-memory store used as a safe default and by unit tests. */
class InMemoryAdbConnectionStore : AdbConnectionStore {
    @Volatile
    private var port: Int? = null

    override fun saveConnectedPort(port: Int) {
        this.port = port
    }

    override fun loadConnectedPort(): Int? = port

    override fun clearConnectedPort() {
        port = null
    }
}
