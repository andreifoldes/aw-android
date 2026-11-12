package net.activitywatch.android.automation

import java.net.URLEncoder

/**
 * `{{placeholder}}` substitution for the URL, headers and body of an automation's request.
 *
 * Values are escaped for where they land, so an app name containing a quote cannot break
 * a JSON body and one containing `&` cannot break a query string. Unknown placeholders are
 * left as written, so a typo is visible in the request rather than silently empty.
 *
 * Pure logic: no Android APIs, so it is unit-tested on the JVM.
 */
object RequestTemplate {

    enum class Escape { NONE, JSON, URL }

    /** Placeholder names, with a short description for the editor's help text. */
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        "event" to "\"opened\" or \"closed\"",
        "app" to "app name",
        "package" to "package name",
        "category" to "ActivityWatch category, e.g. Work > Programming",
        "android_category" to "Android app category, e.g. Game",
        "timestamp" to "when it happened (ISO 8601, UTC)",
        "epoch_ms" to "when it happened (Unix ms)",
        "session_start" to "session start (ISO 8601, UTC)",
        "duration_seconds" to "session length in seconds (0 on open)",
        "rule" to "this automation's name",
        "device" to "device hostname",
    )

    private val PLACEHOLDER = Regex("""\{\{\s*([a-zA-Z_]+)\s*\}\}""")

    fun render(template: String, vars: Map<String, String>, escape: Escape): String =
        PLACEHOLDER.replace(template) { match ->
            val value = vars[match.groupValues[1].lowercase()] ?: return@replace match.value
            when (escape) {
                Escape.NONE -> value
                Escape.JSON -> jsonEscape(value)
                Escape.URL -> URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
        }

    /** Escapes [value] for use *inside* a JSON string literal (no surrounding quotes added). */
    fun jsonEscape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
    }

    /**
     * Parses the editor's headers box: one `Name: Value` per line. Blank lines and lines
     * without a name are skipped.
     */
    fun parseHeaders(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val name = line.substring(0, idx).trim()
            if (name.isEmpty() || name.any { it.isWhitespace() }) return@mapNotNull null
            name to line.substring(idx + 1).trim()
        }

    /** The body is escaped as JSON when the request says it is JSON or it looks like JSON. */
    fun bodyEscape(headers: List<Pair<String, String>>, body: String): Escape {
        val contentType = headers.lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        if (contentType != null) {
            return if (contentType.contains("json", ignoreCase = true)) Escape.JSON else Escape.NONE
        }
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) Escape.JSON else Escape.NONE
    }
}
