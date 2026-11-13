package net.activitywatch.android.automation

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists automation rules, the polling interval, and each rule's last run result.
 * Kept in its own SharedPreferences file so it cannot collide with AWPreferences keys.
 */
class AutomationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRules(): List<AutomationRule> = AutomationRule.listFromJson(prefs.getString(KEY_RULES, null))

    fun getRule(id: String): AutomationRule? = getRules().firstOrNull { it.id == id }

    fun hasEnabledRules(): Boolean = getRules().any { it.enabled }

    @Synchronized
    fun saveRule(rule: AutomationRule) {
        val rules = getRules().toMutableList()
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) rules[idx] = rule else rules += rule
        writeRules(rules)
    }

    @Synchronized
    fun deleteRule(id: String) {
        writeRules(getRules().filterNot { it.id == id })
        prefs.edit().remove(KEY_RESULT_PREFIX + id).apply()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        writeRules(getRules().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun writeRules(rules: List<AutomationRule>) {
        prefs.edit().putString(KEY_RULES, AutomationRule.listToJson(rules)).apply()
    }

    fun getPollIntervalMs(): Long {
        val stored = prefs.getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_MS)
        return stored.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)
    }

    fun setPollIntervalMs(ms: Long) {
        prefs.edit().putLong(KEY_POLL_INTERVAL, ms.coerceIn(MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS)).apply()
    }

    /** Last outcome of a rule, e.g. "HTTP 200" or "Failed: timeout", with when it ran. */
    data class RunResult(val at: Long, val success: Boolean, val message: String)

    fun getLastResult(id: String): RunResult? {
        val raw = prefs.getString(KEY_RESULT_PREFIX + id, null) ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        return RunResult(at, parts[1] == "1", parts[2])
    }

    fun setLastResult(id: String, result: RunResult) {
        val raw = "${result.at}|${if (result.success) "1" else "0"}|${result.message}"
        prefs.edit().putString(KEY_RESULT_PREFIX + id, raw).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val PREFS_NAME = "AutomationRules"
        const val KEY_RULES = "rules"
        const val KEY_POLL_INTERVAL = "pollIntervalMs"
        const val KEY_RESULT_PREFIX = "lastResult."

        const val DEFAULT_POLL_INTERVAL_MS = 1000L
        const val MIN_POLL_INTERVAL_MS = 500L
        const val MAX_POLL_INTERVAL_MS = 10_000L

        /** Choices offered in the polling dropdown: (ms, label). */
        val POLL_INTERVAL_CHOICES: List<Pair<Long, String>> = listOf(
            500L to "Every 0.5 s",
            1000L to "Every 1 s",
            2000L to "Every 2 s",
            5000L to "Every 5 s",
            10_000L to "Every 10 s",
        )
    }
}
