package net.activitywatch.android.automation

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** IF part: which session edge fires the rule. */
enum class TriggerType(val label: String) {
    OPENED("Opens"),
    CLOSED("Closes"),
    EITHER("Opens or closes"),
}

/** Which apps the rule applies to. */
enum class TargetType(val label: String) {
    APP("Specific app"),
    ANDROID_CATEGORY("Android category"),
    AW_CATEGORY("ActivityWatch category"),
    ANY("Any app"),
}

/** Request body encoding, as in Shortcuts' "Get Contents of URL". */
enum class BodyType(val label: String) {
    JSON("JSON"),
    FORM("Form"),
    TEXT("Text"),
}

/** Type of one JSON body field. Form fields are always text. */
enum class FieldType(val label: String) {
    TEXT("Text"),
    NUMBER("Number"),
    BOOLEAN("Boolean"),
}

data class KeyValue(val key: String, val value: String)

data class BodyField(val key: String, val value: String, val type: FieldType = FieldType.TEXT)

/** The facts about an app that rules match against. */
data class SessionApp(
    val packageName: String,
    val label: String,
    /** android.content.pm.ApplicationInfo.category, or -1 (CATEGORY_UNDEFINED). */
    val androidCategory: Int,
    val androidCategoryLabel: String,
    /** ActivityWatch category path; null when uncategorized. */
    val awCategory: List<String>?,
)

/**
 * One "WHEN an app session of <target> <opens/closes> DO get contents of <url>" automation.
 *
 * [targetValue] is the package name (APP), the ApplicationInfo category number as a
 * string (ANDROID_CATEGORY), or the category path as a JSON array (AW_CATEGORY).
 * [targetLabel] is only for display.
 *
 * The request mirrors Shortcuts: [headers] are key/value rows, and the body is either
 * typed key/value [bodyFields] sent as JSON or a form, or free [body] text.
 */
data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: TriggerType = TriggerType.OPENED,
    val targetType: TargetType = TargetType.APP,
    val targetValue: String = "",
    val targetLabel: String = "",
    /** For closes: skip sessions shorter than this. 0 = no minimum. */
    val minDurationSeconds: Int = 0,
    val method: String = "POST",
    val url: String = "",
    val headers: List<KeyValue> = emptyList(),
    val bodyType: BodyType = BodyType.JSON,
    val bodyFields: List<BodyField> = DEFAULT_FIELDS,
    /** Raw body, used when [bodyType] is TEXT. */
    val body: String = "",
) {
    fun matches(transition: SessionTransition, app: SessionApp): Boolean {
        if (!enabled) return false
        val edgeMatches = when (trigger) {
            TriggerType.OPENED -> transition is SessionTransition.Opened
            TriggerType.CLOSED -> transition is SessionTransition.Closed
            TriggerType.EITHER -> true
        }
        if (!edgeMatches) return false
        if (transition is SessionTransition.Closed && transition.durationMs < minDurationSeconds * 1000L) {
            return false
        }
        return matchesApp(app)
    }

    fun matchesApp(app: SessionApp): Boolean = when (targetType) {
        TargetType.ANY -> true
        TargetType.APP -> app.packageName == targetValue
        TargetType.ANDROID_CATEGORY -> targetValue.toIntOrNull()?.let { it == app.androidCategory } ?: false
        TargetType.AW_CATEGORY -> {
            // Selecting a parent category also matches its subcategories.
            val wanted = awCategoryPath()
            val actual = app.awCategory
            wanted.isNotEmpty() && actual != null && actual.size >= wanted.size &&
                actual.subList(0, wanted.size) == wanted
        }
    }

    fun awCategoryPath(): List<String> = try {
        val arr = JSONArray(targetValue)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    /** True when the request mentions a placeholder (cheap check before resolving it). */
    fun mentions(placeholder: String): Boolean =
        sequenceOf(url, body).plus(headers.flatMap { listOf(it.key, it.value) })
            .plus(bodyFields.flatMap { listOf(it.key, it.value) })
            .any { placeholder in it }

    /** "When Game opens" */
    fun whenSummary(): String {
        val edge = when (trigger) {
            TriggerType.OPENED -> "opens"
            TriggerType.CLOSED -> "closes"
            TriggerType.EITHER -> "opens or closes"
        }
        val target = when (targetType) {
            TargetType.ANY -> "any app"
            TargetType.APP -> targetLabel.ifEmpty { targetValue }
            TargetType.ANDROID_CATEGORY -> "a ${targetLabel.ifEmpty { "category" }} app"
            TargetType.AW_CATEGORY -> "a \"${targetLabel.ifEmpty { "category" }}\" app"
        }
        return "When $target $edge"
    }

    /** "POST https://example.com/hook" */
    fun doSummary(): String = "$method ${url.ifEmpty { "(no URL)" }}"

    /** One-line description: "When Game opens → POST https://example.com/hook". */
    fun summary(): String = "${whenSummary()} → ${doSummary()}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("trigger", trigger.name)
        .put("targetType", targetType.name)
        .put("targetValue", targetValue)
        .put("targetLabel", targetLabel)
        .put("minDurationSeconds", minDurationSeconds)
        .put("method", method)
        .put("url", url)
        .put("headers", JSONArray().apply {
            headers.forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
        })
        .put("bodyType", bodyType.name)
        .put("bodyFields", JSONArray().apply {
            bodyFields.forEach {
                put(JSONObject().put("key", it.key).put("value", it.value).put("type", it.type.name))
            }
        })
        .put("body", body)

    companion object {
        val METHODS = listOf("POST", "GET", "PUT", "PATCH", "DELETE")

        val DEFAULT_FIELDS = listOf(
            BodyField("event", "{{event}}"),
            BodyField("app", "{{app}}"),
            BodyField("package", "{{package}}"),
            BodyField("category", "{{category}}"),
            BodyField("timestamp", "{{timestamp}}"),
            BodyField("duration_seconds", "{{duration_seconds}}", FieldType.NUMBER),
        )

        fun fromJson(json: JSONObject): AutomationRule {
            val rawBody = json.optString("body")
            return AutomationRule(
                id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = json.optString("name"),
                enabled = json.optBoolean("enabled", true),
                trigger = enumOr(json.optString("trigger"), TriggerType.OPENED),
                targetType = enumOr(json.optString("targetType"), TargetType.APP),
                targetValue = json.optString("targetValue"),
                targetLabel = json.optString("targetLabel"),
                minDurationSeconds = json.optInt("minDurationSeconds", 0),
                method = json.optString("method").ifEmpty { "POST" },
                url = json.optString("url"),
                headers = parseHeaders(json.opt("headers")),
                // Rules saved before body types existed only had a raw body.
                bodyType = enumOr(json.optString("bodyType"), if (json.has("bodyType")) BodyType.JSON else BodyType.TEXT),
                bodyFields = json.optJSONArray("bodyFields")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        BodyField(it.optString("key"), it.optString("value"), enumOr(it.optString("type"), FieldType.TEXT))
                    }
                } ?: emptyList(),
                body = rawBody,
            )
        }

        private fun parseHeaders(value: Any?): List<KeyValue> = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                .map { KeyValue(it.optString("key"), it.optString("value")) }
            // Earlier format: one "Name: Value" per line.
            is String -> RequestTemplate.parseHeaders(value).map { KeyValue(it.first, it.second) }
            else -> emptyList()
        }

        fun listToJson(rules: List<AutomationRule>): String =
            JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<AutomationRule> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
