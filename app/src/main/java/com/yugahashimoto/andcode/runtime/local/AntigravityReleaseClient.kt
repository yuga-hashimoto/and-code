package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A concrete Antigravity CLI release the app can download and verify. */
data class AntigravityRelease(
    val version: String,
    val asset: AntigravityAsset,
)

/**
 * Resolves the newest official Antigravity CLI release from GitHub.
 *
 * The app no longer hardcodes which release users get: this reads Google's `latest` release the
 * same way [LocalRuntimeReleaseClient] does for OpenCode, taking the asset's GitHub-reported
 * SHA-256 digest as the verification pin. A payload without a digest is rejected rather than
 * trusted, so a future API change degrades to [resolveAntigravityRelease]'s pinned fallback
 * instead of to an unverified binary.
 */
class AntigravityReleaseClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpoint: HttpUrl = OFFICIAL_RELEASE_ENDPOINT.toHttpUrl(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    init {
        require(endpoint.isHttps || endpoint.host in LOOPBACK_HOSTS) {
            "Antigravity release API must use HTTPS"
        }
    }

    suspend fun latest(abi: String): AntigravityRelease =
        withContext(Dispatchers.IO) {
            val assetName =
                requireNotNull(ASSET_NAME_BY_ABI[abi]) {
                    "Unsupported Android ABI for Antigravity updates: $abi"
                }
            val request =
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", GITHUB_ACCEPT)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            val releaseDto =
                httpClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) {
                        "Antigravity release check failed with HTTP ${response.code}"
                    }
                    val body =
                        requireNotNull(response.body) {
                            "Antigravity release response had no body"
                        }
                    json.decodeFromString<GitHubReleaseDto>(body.string())
                }
            val version = normalizeRuntimeVersion(releaseDto.tagName)
            val assetDto =
                requireNotNull(releaseDto.assets.firstOrNull { it.name == assetName }) {
                    "Antigravity release $version does not contain $assetName"
                }
            val digest = assetDto.digest
            require(digest != null && SHA256_DIGEST.matches(digest)) {
                "Antigravity release asset is missing a valid SHA-256 digest"
            }
            val assetUrl = assetDto.downloadUrl.toHttpUrl()
            require(assetUrl.isHttps) { "Antigravity release asset URL must use HTTPS" }
            require(assetDto.size > 0L) { "Antigravity release asset size must be positive" }

            AntigravityRelease(
                version = version,
                asset =
                    AntigravityAsset(
                        name = assetDto.name,
                        url = assetUrl.toString(),
                        sha256 = digest.removePrefix(SHA256_PREFIX),
                        sizeBytes = assetDto.size,
                    ),
            )
        }

    @Serializable
    private data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("assets") val assets: List<GitHubReleaseAssetDto> = emptyList(),
    )

    @Serializable
    private data class GitHubReleaseAssetDto(
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("digest") val digest: String?,
    )

    companion object {
        const val OFFICIAL_RELEASE_ENDPOINT =
            "https://api.github.com/repos/google-antigravity/antigravity-cli/releases/latest"
        private const val GITHUB_ACCEPT = "application/vnd.github+json"
        private const val USER_AGENT = "AndCode"
        private const val SHA256_PREFIX = "sha256:"
        private val SHA256_DIGEST = Regex("^sha256:[a-f0-9]{64}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private val ASSET_NAME_BY_ABI =
            mapOf(
                "arm64-v8a" to "agy_cli_linux_arm64.tar.gz",
                "x86_64" to "agy_cli_linux_x64.tar.gz",
            )
    }
}

/**
 * The release an install or update should fetch: GitHub's latest, or this build's pin.
 *
 * Every failure of the lookup — offline device, rate limit, a payload without a usable digest —
 * degrades to [AntigravityManifest] rather than failing the install, so provisioning keeps working
 * exactly as it did before dynamic resolution existed. The pin is verified against hashes compiled
 * into this build; a dynamic release is verified against GitHub's own digest over HTTPS.
 */
suspend fun resolveAntigravityRelease(
    abi: String,
    httpClient: OkHttpClient,
    endpoint: HttpUrl = AntigravityReleaseClient.OFFICIAL_RELEASE_ENDPOINT.toHttpUrl(),
): AntigravityRelease =
    try {
        AntigravityReleaseClient(httpClient, endpoint).latest(abi)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        AntigravityRelease(AntigravityManifest.VERSION, AntigravityManifest.assetFor(abi))
    }

/**
 * Whether installing [latestVersion] over [currentVersion] would change anything.
 *
 * An unknown current version (no marker yet, so what the guest runs is guesswork) always installs:
 * that is also how a garbage or unreadable marker gets repaired. Equal or older versions skip the
 * ~90 MB download — including the case where the lookup degraded to this build's pin while the
 * guest already runs something newer, which must not become a silent downgrade.
 */
internal fun shouldReplaceInstalledAntigravity(
    currentVersion: String?,
    latestVersion: String,
): Boolean {
    if (currentVersion.isNullOrBlank()) return true
    return runCatching {
        compareRuntimeVersions(normalizeRuntimeVersion(latestVersion), normalizeRuntimeVersion(currentVersion)) > 0
    }.getOrDefault(true)
}
