package net.activitywatch.android.automation

import java.net.URLEncoder

/** A fully rendered request, ready to send. [body] is null when nothing is sent. */
data class BuiltRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
)

/**
 * Renders an [AutomationRule] into the request it sends, like Shortcuts' "Get Contents of
 * URL": header rows, and a JSON / Form / Text body. Placeholder values are escaped for
 * where they land, so app names with quotes or `&` cannot break the request.
 *
 * A Content-Type matching the body is added unless the user set one. GET sends no body.
 *
 * Pure logic: no Android APIs, so it is unit-tested on the JVM.
 */
object RequestBuilder {

    private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
    private val TRUE_WORDS = setOf("true", "yes", "on", "1")

    fun build(rule: AutomationRule, vars: Map<String, String>): BuiltRequest {
        val method = rule.method.uppercase()
        val url = RequestTemplate.render(rule.url.trim(), vars, RequestTemplate.Escape.URL)
        val headers = rule.headers
            .filter { it.key.isNotBlank() }
            .map { (k, v) -> clean(render(k, vars)) to clean(render(v, vars)) }
            .toMutableList()

        if (method == "GET") return BuiltRequest(method, url, headers, null)

        val (body, contentType) = when (rule.bodyType) {
            BodyType.JSON -> jsonBody(rule.bodyFields, vars) to "application/json"
            BodyType.FORM -> formBody(rule.bodyFields, vars) to "application/x-www-form-urlencoded"
            BodyType.TEXT -> {
                if (rule.body.isBlank()) {
                    null to null
                } else {
                    val escape = RequestTemplate.bodyEscape(headers, rule.body)
                    RequestTemplate.render(rule.body, vars, escape) to
                        if (escape == RequestTemplate.Escape.JSON) "application/json" else "text/plain; charset=utf-8"
                }
            }
        }
        if (body != null && contentType != null && headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            headers += "Content-Type" to contentType
        }
        return BuiltRequest(method, url, headers, body)
    }

    /** Fields in the order the user listed them; rows without a key are skipped. */
    fun jsonBody(fields: List<BodyField>, vars: Map<String, String>): String? {
        val rows = fields.filter { it.key.isNotBlank() }
        if (rows.isEmpty()) return null
        return rows.joinToString(separator = ", ", prefix = "{", postfix = "}") { field ->
            val key = RequestTemplate.jsonEscape(render(field.key, vars))
            val value = render(field.value, vars).trim()
            val json = when (field.type) {
                FieldType.TEXT -> quoted(render(field.value, vars))
                // Not a number after substitution: send it as text rather than invalid JSON.
                FieldType.NUMBER -> if (JSON_NUMBER.matches(value)) value else quoted(value)
                FieldType.BOOLEAN -> (value.lowercase() in TRUE_WORDS).toString()
            }
            "\"$key\": $json"
        }
    }

    fun formBody(fields: List<BodyField>, vars: Map<String, String>): String? {
        val rows = fields.filter { it.key.isNotBlank() }
        if (rows.isEmpty()) return null
        return rows.joinToString("&") { "${encode(render(it.key, vars))}=${encode(render(it.value, vars))}" }
    }

    private fun render(text: String, vars: Map<String, String>) =
        RequestTemplate.render(text, vars, RequestTemplate.Escape.NONE)

    private fun quoted(text: String) = "\"${RequestTemplate.jsonEscape(text)}\""

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    // A header value must stay on one line.
    private fun clean(text: String) = text.replace(Regex("[\r\n]+"), " ").trim()
}
