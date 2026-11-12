package net.activitywatch.android.automation

import net.activitywatch.android.automation.RequestTemplate.Escape
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestTemplateTest {

    private val vars = mapOf(
        "app" to "Tom's \"Game\" & Co",
        "event" to "opened",
        "duration_seconds" to "42",
    )

    @Test
    fun substitutesPlaceholdersWithOptionalSpaces() {
        assertEquals("opened / opened", RequestTemplate.render("{{event}} / {{ event }}", vars, Escape.NONE))
    }

    @Test
    fun placeholderNamesAreCaseInsensitive() {
        assertEquals("opened", RequestTemplate.render("{{EVENT}}", vars, Escape.NONE))
    }

    @Test
    fun unknownPlaceholdersAreLeftAsWritten() {
        assertEquals("{{nope}}", RequestTemplate.render("{{nope}}", vars, Escape.NONE))
    }

    @Test
    fun jsonEscapingKeepsBodyValidJson() {
        val body = """{"app": "{{app}}", "d": {{duration_seconds}}}"""
        val rendered = RequestTemplate.render(body, vars, Escape.JSON)
        val parsed = JSONObject(rendered)
        assertEquals("Tom's \"Game\" & Co", parsed.getString("app"))
        assertEquals(42, parsed.getInt("d"))
    }

    @Test
    fun jsonEscapeHandlesControlCharacters() {
        assertEquals("a\\nb\\t\\\\\\u0001", RequestTemplate.jsonEscape("a\nb\t\\\u0001"))
    }

    @Test
    fun urlEscapingEncodesQueryValues() {
        assertEquals(
            "https://x.test/hook?app=Tom%27s%20%22Game%22%20%26%20Co",
            RequestTemplate.render("https://x.test/hook?app={{app}}", vars, Escape.URL),
        )
    }

    @Test
    fun parsesHeadersSkippingBlankAndMalformedLines() {
        val headers = RequestTemplate.parseHeaders(
            "Content-Type: application/json\n\nAuthorization: Bearer a:b\nno colon here\n: novalue\nBad Name: x"
        )
        assertEquals(
            listOf("Content-Type" to "application/json", "Authorization" to "Bearer a:b"),
            headers,
        )
    }

    @Test
    fun bodyEscapeFollowsContentType() {
        assertEquals(Escape.JSON, RequestTemplate.bodyEscape(listOf("content-type" to "application/json; charset=utf-8"), "x"))
        assertEquals(Escape.NONE, RequestTemplate.bodyEscape(listOf("Content-Type" to "text/plain"), "{\"a\":1}"))
    }

    @Test
    fun bodyEscapeSniffsJsonWithoutContentType() {
        assertEquals(Escape.JSON, RequestTemplate.bodyEscape(emptyList(), "  {\"a\": 1}"))
        assertEquals(Escape.NONE, RequestTemplate.bodyEscape(emptyList(), "app={{app}}"))
    }
}
