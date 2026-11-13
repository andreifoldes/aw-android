package net.activitywatch.android.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwCategoriesTest {

    private val setting = """
        [
          {"name": ["Work"], "rule": {"type": "regex", "regex": "Docs|Sheets"}},
          {"name": ["Work", "Programming"], "rule": {"type": "regex", "regex": "github|termux", "ignore_case": true}},
          {"name": ["Media", "Games"], "rule": {"type": "regex", "regex": "Minecraft|com\\.supercell\\."}},
          {"name": ["Media", "Video"], "rule": {"type": "regex", "regex": "YouTube"}},
          {"name": ["Comms"], "rule": {"type": "none"}},
          {"name": ["Broken"], "rule": {"type": "regex", "regex": "(unclosed"}}
        ]
    """.trimIndent()

    private val categories = AwCategories.parse(setting)!!

    @Test
    fun matchesAppNameOrPackage() {
        assertEquals(listOf("Media", "Video"), categories.classify("YouTube", "com.google.android.youtube"))
        assertEquals(listOf("Media", "Games"), categories.classify("Clash of Clans", "com.supercell.clashofclans"))
    }

    @Test
    fun deepestMatchWins() {
        assertEquals(listOf("Work", "Programming"), categories.classify("GitHub Docs", "com.github.android"))
    }

    @Test
    fun ignoreCaseIsHonoured() {
        assertEquals(listOf("Work", "Programming"), categories.classify("Termux", "com.termux"))
        assertNull(categories.classify("youtube", "x")) // YouTube rule is case-sensitive
    }

    @Test
    fun unmatchedIsUncategorized() {
        assertNull(categories.classify("Calculator", "com.android.calculator2"))
    }

    @Test
    fun invalidRegexDoesNotBreakOtherCategories() {
        assertEquals(listOf("Work"), categories.classify("Google Docs", "com.google.docs"))
    }

    @Test
    fun allPathsIncludesImpliedParentsInOrder() {
        assertEquals(
            listOf(
                listOf("Work"), listOf("Work", "Programming"),
                listOf("Media"), listOf("Media", "Games"), listOf("Media", "Video"),
                listOf("Comms"), listOf("Broken"),
            ),
            categories.allPaths(),
        )
    }

    @Test
    fun unsetOrInvalidSettingParsesToNull() {
        assertNull(AwCategories.parse(null))
        assertNull(AwCategories.parse("null"))
        assertNull(AwCategories.parse("{\"error\": \"x\"}"))
        assertNull(AwCategories.parse("[not json"))
    }

    @Test
    fun formatsPath() {
        assertEquals("Media > Games", AwCategories.formatPath(listOf("Media", "Games")))
    }
}
