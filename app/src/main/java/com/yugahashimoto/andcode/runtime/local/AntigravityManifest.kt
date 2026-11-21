package com.yugahashimoto.andcode.runtime.local

import java.io.File

data class AntigravityAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Pinned official Google release metadata. The Termux fork is intentionally not an asset source. */
object AntigravityManifest {
    const val VERSION = "1.5.13"
    const val BINARY_NAME = "agy"

    // Peak update use is the ~92 MB archive + ~225 MB extracted binary + ~225 MB `agy.new` copy
    // + ~225 MB `agy.rollback`, about 770 MB; 800 MB leaves headroom.
    const val MIN_FREE_BYTES = 800L * 1024L * 1024L
    private const val BASE = "https://github.com/google-antigravity/antigravity-cli/releases/download/1.5.13/"

    val arm64 =
        AntigravityAsset(
            name = "agy_cli_linux_arm64.tar.gz",
            url = BASE + "agy_cli_linux_arm64.tar.gz",
            sha256 = "36596ad8038531fd75f0e97b5ba8967a30f2fd67f8d9c61959d2e4761d7b215f",
            sizeBytes = 91_596_782L,
        )
    val x64 =
        AntigravityAsset(
            name = "agy_cli_linux_x64.tar.gz",
            url = BASE + "agy_cli_linux_x64.tar.gz",
            sha256 = "7fa9cc6df0d09562693d637c406a86c1b0f52ab3364714b1fd2d79cc6060d4b2",
            sizeBytes = 100_548_668L,
        )

    fun assetFor(abi: String): AntigravityAsset =
        when (abi) {
            "arm64-v8a", "aarch64" -> arm64
            "x86_64", "amd64" -> x64
            else -> error("Antigravity official Linux release does not support ABI $abi")
        }

    fun verifyArchive(
        file: File,
        abi: String,
    ) {
        RuntimeArchive.verifySha256(file, assetFor(abi).sha256)
    }
}
