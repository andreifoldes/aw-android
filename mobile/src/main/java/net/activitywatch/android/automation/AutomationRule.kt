package net.activitywatch.android.automation

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** IF part: which session edge fires the rule. */
enum class TriggerType(val label: String) {
    OPENED("App session starts (opened)"),
    CLOSED("App session ends (closed)"),
    EITHER("App session starts or ends"),
}

/** Which apps the rule applies to. */
enum class TargetType(val label: String) {
    APP("A specific app"),
    ANDROID_CATEGORY("An Android app category"),
    AW_CATEGORY("An ActivityWatch category"),
    ANY("Any app"),
}

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
 * One "IF an app session of <target> <opens/closes> THEN call <url>" automation.
 *
 * [targetValue] is the package name (APP), the ApplicationInfo category number as a
 * string (ANDROID_CATEGORY), or the category path as a JSON array (AW_CATEGORY).
 * [targetLabel] is only for display.
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
    val headers: String = DEFAULT_HEADERS,
    val body: String = DEFAULT_BODY,
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

    /** One-line description for the rules list. */
    fun summary(): String {
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
        return "When $target $edge → $method ${url.ifEmpty { "(no URL)" }}"
    }

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
        .put("headers", headers)
        .put("body", body)

    companion object {
        val METHODS = listOf("POST", "GET", "PUT", "PATCH", "DELETE")

        const val DEFAULT_HEADERS = "Content-Type: application/json"
        val DEFAULT_BODY = """
            {
              "event": "{{event}}",
              "app": "{{app}}",
              "package": "{{package}}",
              "category": "{{category}}",
              "timestamp": "{{timestamp}}",
              "duration_seconds": {{duration_seconds}}
            }
        """.trimIndent()

        fun fromJson(json: JSONObject): AutomationRule = AutomationRule(
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
            headers = json.optString("headers"),
            body = json.optString("body"),
        )

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
