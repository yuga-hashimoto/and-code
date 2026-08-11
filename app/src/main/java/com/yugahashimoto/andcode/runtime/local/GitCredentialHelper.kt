package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Sets up GitHub git credentials inside the runtime rootfs.
 *
 * Two mechanisms are configured so git works regardless of which credential
 * helper the gitconfig ends up using (OpenCode itself may switch it to "store"):
 *  1. A `git-credential-opencode` helper script that reads OPENCODE_GITHUB_TOKEN
 *     from the environment (registered as `helper = opencode`).
 *  2. A `~/.git-credentials` store file (used by `helper = store`).
 */
class GitCredentialHelper(
    private val rootfs: File,
    private val token: () -> String?,
) {
    private val helperFile
        get() = File(rootfs, "usr/local/bin/git-credential-opencode")
    private val gitConfigFile
        get() = File(rootfs, "root/.gitconfig")
    private val gitCredentialsFile
        get() = File(rootfs, "root/.git-credentials")

    fun install(): File {
        helperFile.parentFile?.mkdirs()
        helperFile.writeText(
            """
            #!/bin/sh
            case "${'$'}1" in
              get)
                read protocol
                read host
                token="${'$'}OPENCODE_GITHUB_TOKEN"
                [ -n "${'$'}token" ] && printf 'protocol=%s\nhost=%s\nusername=x-access-token\npassword=%s\n\n' "${'$'}protocol" "${'$'}host" "${'$'}token"
                ;;
            esac
            """.trimIndent(),
        )
        helperFile.setExecutable(true, true)
        helperFile.setReadable(true, true)
        installGitHubCredentialConfig()
        writeCredentialStore()
        return helperFile
    }

    fun remove() {
        helperFile.delete()
        gitCredentialsFile.delete()
        removeGitHubCredentialConfig()
    }

    private fun writeCredentialStore() {
        val current = token()
        if (current.isNullOrBlank()) {
            gitCredentialsFile.delete()
            return
        }
        gitCredentialsFile.parentFile?.mkdirs()
        gitCredentialsFile.writeText("https://x-access-token:$current@github.com\n")
        gitCredentialsFile.setReadable(false, false)
        gitCredentialsFile.setReadable(true, true)
        gitCredentialsFile.setWritable(false, false)
        gitCredentialsFile.setWritable(true, true)
    }

    private fun installGitHubCredentialConfig() {
        val existing = gitConfigFile.takeIf(File::isFile)?.readText().orEmpty()
        val cleaned =
            existing
                .replace(GITHUB_CREDENTIAL_SECTION_MALFORMED, "")
                .replace(GITHUB_CREDENTIAL_SECTION, "")
                .trim()
        gitConfigFile.parentFile?.mkdirs()
        gitConfigFile.writeText(
            listOf(cleaned, GITHUB_CREDENTIAL_SECTION)
                .filter(String::isNotEmpty)
                .joinToString("\n\n", postfix = "\n"),
        )
    }

    private fun removeGitHubCredentialConfig() {
        if (!gitConfigFile.isFile) return
        val remaining =
            gitConfigFile.readText()
                .replace(GITHUB_CREDENTIAL_SECTION, "")
                .replace(GITHUB_CREDENTIAL_SECTION_MALFORMED, "")
                .trim()
        if (remaining.isEmpty()) gitConfigFile.delete() else gitConfigFile.writeText("$remaining\n")
    }

    private companion object {
        const val GITHUB_CREDENTIAL_SECTION = "[credential \"https://github.com\"]\n\thelper = opencode"
        const val GITHUB_CREDENTIAL_SECTION_MALFORMED = "[credential \"https://github.com\"]\n\\thelper = opencode"
    }
}
