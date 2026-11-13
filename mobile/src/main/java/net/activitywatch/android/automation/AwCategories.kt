package net.activitywatch.android.automation

import org.json.JSONArray

/**
 * The user's ActivityWatch categories (the aw-webui `classes` setting), used to match
 * app sessions by category the same way the dashboard does.
 *
 * Like aw-server's `categorize`, a regex rule matches when it matches any of the event's
 * text fields (here the app name and package), and the most specific (deepest) matching
 * category wins; on a tie the earlier one is kept.
 *
 * Pure logic apart from org.json, so it is unit-tested on the JVM.
 */
class AwCategories(val categories: List<Category>) {

    class Category(val path: List<String>, val regex: Regex?)

    /** The category path for an app, or null if no category matches (Uncategorized). */
    fun classify(vararg texts: String): List<String>? {
        var best: Category? = null
        for (category in categories) {
            val regex = category.regex ?: continue
            if (texts.none { regex.containsMatchIn(it) }) continue
            if (best == null || category.path.size > best.path.size) best = category
        }
        return best?.path
    }

    /**
     * Every selectable category path, parents included (a parent that only exists through
     * its children is still a valid target), in the order they were defined.
     */
    fun allPaths(): List<List<String>> {
        val seen = LinkedHashSet<List<String>>()
        for (category in categories) {
            for (depth in 1..category.path.size) seen += category.path.subList(0, depth)
        }
        return seen.toList()
    }

    companion object {
        /** Parses the setting; returns null when it is unset or not a category list. */
        fun parse(settingJson: String?): AwCategories? {
            val v = settingJson?.trim() ?: return null
            if (!v.startsWith("[")) return null
            val array = try {
                JSONArray(v)
            } catch (e: Exception) {
                return null
            }
            val categories = mutableListOf<Category>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val nameArr = obj.optJSONArray("name") ?: continue
                val path = (0 until nameArr.length()).map { nameArr.optString(it) }.filter { it.isNotEmpty() }
                if (path.isEmpty()) continue
                val rule = obj.optJSONObject("rule")
                val regex = if (rule?.optString("type") == "regex") {
                    compile(rule.optString("regex"), rule.optBoolean("ignore_case", false))
                } else {
                    null
                }
                categories += Category(path, regex)
            }
            return AwCategories(categories)
        }

        private fun compile(pattern: String, ignoreCase: Boolean): Regex? {
            if (pattern.isEmpty()) return null
            return try {
                if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
            } catch (e: Exception) {
                // The webui accepts JS regex syntax; a pattern Java can't compile just
                // doesn't match, rather than disabling every category.
                null
            }
        }

        fun formatPath(path: List<String>): String = path.joinToString(" > ")
    }
}
