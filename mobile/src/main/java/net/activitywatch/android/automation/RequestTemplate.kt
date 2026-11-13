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

    /** A value the request can include, shown as a tappable chip in the editor. */
    data class Placeholder(val name: String, val label: String, val description: String) {
        val token: String get() = "{{$name}}"
    }

    val PLACEHOLDERS: List<Placeholder> = listOf(
        Placeholder("app", "App", "app name"),
        Placeholder("event", "Event", "\"opened\" or \"closed\""),
        Placeholder("package", "Package", "package name"),
        Placeholder("category", "Category", "ActivityWatch category, e.g. Work > Programming"),
        Placeholder("android_category", "Android category", "Android app category, e.g. Game"),
        Placeholder("timestamp", "Timestamp", "when it happened (ISO 8601, UTC)"),
        Placeholder("epoch_ms", "Epoch ms", "when it happened (Unix ms)"),
        Placeholder("session_start", "Session start", "session start (ISO 8601, UTC)"),
        Placeholder("duration_seconds", "Duration (s)", "session length in seconds (0 on open)"),
        Placeholder("rule", "Automation", "this automation's name"),
        Placeholder("device", "Device", "device hostname"),
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
        // A leading {{placeholder}} also starts with "{", so look past placeholders.
        val trimmed = PLACEHOLDER.replace(body, "x").trimStart()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) Escape.JSON else Escape.NONE
    }
}
