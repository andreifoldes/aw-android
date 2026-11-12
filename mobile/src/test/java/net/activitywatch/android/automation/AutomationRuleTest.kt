package net.activitywatch.android.automation

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuleTest {

    private val game = SessionApp("com.example.game", "Game", 0, "Games", listOf("Media", "Games"))
    private val notes = SessionApp("com.example.notes", "Notes", 7, "Productivity", null)

    private val opened = SessionTransition.Opened("x", 1000)
    private fun closed(seconds: Long) = SessionTransition.Closed("x", 0, seconds * 1000)

    @Test
    fun triggerSelectsWhichEdgeFires() {
        val onOpen = AutomationRule(trigger = TriggerType.OPENED, targetType = TargetType.ANY)
        val onClose = onOpen.copy(trigger = TriggerType.CLOSED)
        val either = onOpen.copy(trigger = TriggerType.EITHER)
        assertTrue(onOpen.matches(opened, game))
        assertFalse(onOpen.matches(closed(5), game))
        assertFalse(onClose.matches(opened, game))
        assertTrue(onClose.matches(closed(5), game))
        assertTrue(either.matches(opened, game) && either.matches(closed(5), game))
    }

    @Test
    fun disabledRuleNeverMatches() {
        assertFalse(AutomationRule(enabled = false, targetType = TargetType.ANY).matches(opened, game))
    }

    @Test
    fun specificAppMatchesByPackage() {
        val rule = AutomationRule(targetType = TargetType.APP, targetValue = "com.example.game")
        assertTrue(rule.matchesApp(game))
        assertFalse(rule.matchesApp(notes))
    }

    @Test
    fun androidCategoryMatchesByNumber() {
        val rule = AutomationRule(targetType = TargetType.ANDROID_CATEGORY, targetValue = "7")
        assertTrue(rule.matchesApp(notes))
        assertFalse(rule.matchesApp(game))
    }

    @Test
    fun awCategoryMatchesItselfAndSubcategories() {
        val media = AutomationRule(targetType = TargetType.AW_CATEGORY, targetValue = JSONArray(listOf("Media")).toString())
        val games = media.copy(targetValue = JSONArray(listOf("Media", "Games")).toString())
        val work = media.copy(targetValue = JSONArray(listOf("Work")).toString())
        assertTrue(media.matchesApp(game))
        assertTrue(games.matchesApp(game))
        assertFalse(work.matchesApp(game))
        assertFalse(media.matchesApp(notes)) // uncategorized
    }

    @Test
    fun minimumDurationOnlyFiltersCloses() {
        val rule = AutomationRule(trigger = TriggerType.EITHER, targetType = TargetType.ANY, minDurationSeconds = 30)
        assertFalse(rule.matches(closed(29), game))
        assertTrue(rule.matches(closed(30), game))
        assertTrue(rule.matches(opened, game))
    }

    @Test
    fun roundTripsThroughJson() {
        val rule = AutomationRule(
            name = "Log games",
            enabled = false,
            trigger = TriggerType.CLOSED,
            targetType = TargetType.AW_CATEGORY,
            targetValue = """["Media","Games"]""",
            targetLabel = "Media > Games",
            minDurationSeconds = 10,
            method = "PUT",
            url = "https://example.com/{{event}}",
            headers = "X-Token: abc",
            body = "{\"a\": \"{{app}}\"}",
        )
        val restored = AutomationRule.listFromJson(AutomationRule.listToJson(listOf(rule)))
        assertEquals(listOf(rule), restored)
    }

    @Test
    fun unknownEnumValuesFallBackToDefaults() {
        val rule = AutomationRule.fromJson(org.json.JSONObject("""{"id":"1","trigger":"LATER","targetType":"???"}"""))
        assertEquals(TriggerType.OPENED, rule.trigger)
        assertEquals(TargetType.APP, rule.targetType)
        assertEquals("POST", rule.method)
    }

    @Test
    fun corruptStorageYieldsNoRules() {
        assertEquals(emptyList<AutomationRule>(), AutomationRule.listFromJson("not json"))
        assertEquals(emptyList<AutomationRule>(), AutomationRule.listFromJson(null))
    }

    @Test
    fun summaryDescribesRule() {
        val rule = AutomationRule(
            trigger = TriggerType.OPENED, targetType = TargetType.APP,
            targetValue = "com.example.game", targetLabel = "Game", method = "POST", url = "https://h.test/x",
        )
        assertEquals("When Game opens → POST https://h.test/x", rule.summary())
    }

    @Test
    fun awCategoryPathOfMalformedValueIsEmpty() {
        assertEquals(emptyList<String>(), AutomationRule(targetValue = "nope").awCategoryPath())
    }
}
