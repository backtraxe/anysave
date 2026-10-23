package io.github.backtraxe.anysave.log

/**
 * Removes signed-token query values from log lines before they are kept for the
 * in-app diagnostics screen (which can be copied/shared).
 *
 * Cookie and bot-token values are never logged in the first place; this only
 * covers signed media/page URLs (xsec_token, sign=, ...).
 */
object LogSanitizer {

    private val SECRET_QUERY = Regex(
        """([?&])(xsec_token|xsec_source|token|sign|signature|auth|ticket|session|key)=([^&\s]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun sanitize(message: String): String =
        SECRET_QUERY.replace(message) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}=…"
        }
}
