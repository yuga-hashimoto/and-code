package com.yugahashimoto.andcode.core.security

/**
 * Scrubs common credential/token shapes out of free-form text before it reaches a log line, crash
 * report, or any other place that can outlive the process - error messages surfaced by a provider or
 * CLI can otherwise echo back the secret that caused them.
 *
 * This is a best-effort, pattern-based scrub, not a guarantee that every possible credential shape
 * is caught: an unlabeled key, or a provider key format not covered by [PATTERNS], can still slip
 * through. Prefer not to put a provider's raw error text into a message that reaches a log or crash
 * report in the first place; treat this as defense in depth, not the only safeguard. See
 * `PRIVACY.md` for how this limitation is disclosed to users.
 */
object SecretRedaction {
    private val PATTERNS =
        listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}=*") to "Bearer <redacted>",
            Regex("(?i)authorization\\s*[:=]\\s*\\S+") to "authorization=<redacted>",
            Regex("(?i)cookie\\s*[:=]\\s*\\S+") to "cookie=<redacted>",
            Regex("sk-ant-[A-Za-z0-9_-]{10,}") to "sk-ant-<redacted>",
            // Covers plain OpenAI-style keys (sk-<random>) as well as the newer, hyphenated
            // sk-proj-/sk-svcacct-/sk-admin- project-scoped key formats, which a body limited to
            // [A-Za-z0-9] would otherwise leave almost entirely unredacted.
            Regex("sk-[A-Za-z0-9_-]{10,}") to "sk-<redacted>",
            Regex("gh[pousr]_[A-Za-z0-9]{20,}") to "gh_<redacted>",
            Regex("(?i)\\bpassword\\s*[:=]\\s*\\S+") to "password=<redacted>",
            Regex("(?i)\\bapi[_-]?key\\s*[:=]\\s*\\S+") to "api_key=<redacted>",
            Regex("(?i)\\baccess[_-]?token\\s*[:=]\\s*\\S+") to "access_token=<redacted>",
            Regex("(?i)\\btoken\\s*[:=]\\s*\\S+") to "token=<redacted>",
            // Any http(s) URL with a query string, even one not routed through redactUrlQuery()
            // explicitly - the query is where an OAuth code/state/token parameter would live.
            Regex("(https?://[^\\s?#]+)\\?[^\\s]*") to "$1?<redacted>",
        )

    /**
     * Replaces anything that looks like a credential with a fixed placeholder. Best-effort: see the
     * class doc comment above.
     */
    fun redact(text: String): String = PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * Drops everything after `?` or `#`, which is where an OAuth `code`, `state`, or `token` query
     * parameter would live, keeping only the scheme, host, and path for diagnostics.
     */
    fun redactUrlQuery(url: String): String = url.substringBefore('?').substringBefore('#')
}
