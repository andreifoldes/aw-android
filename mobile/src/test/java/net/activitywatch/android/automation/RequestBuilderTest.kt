package net.activitywatch.android.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestBuilderTest {

    private val vars = mapOf(
        "app" to "Tom's \"Game\" & Co",
        "event" to "closed",
        "duration_seconds" to "42",
        "package" to "com.example.game",
    )

    private fun rule(
        method: String = "POST",
        bodyType: BodyType = BodyType.JSON,
        fields: List<BodyField> = emptyList(),
        headers: List<KeyValue> = emptyList(),
        body: String = "",
    ) = AutomationRule(
        url = "https://h.test/hook?app={{app}}", method = method, bodyType = bodyType,
        bodyFields = fields, headers = headers, body = body,
    )

    @Test
    fun jsonBodyKeepsFieldOrderAndTypes() {
        val request = RequestBuilder.build(
            rule(fields = listOf(
                BodyField("app", "{{app}}"),
                BodyField("seconds", "{{duration_seconds}}", FieldType.NUMBER),
                BodyField("long", "yes", FieldType.BOOLEAN),
                BodyField("short", "no", FieldType.BOOLEAN),
            )),
            vars,
        )
        assertEquals(
            """{"app": "Tom's \"Game\" & Co", "seconds": 42, "long": true, "short": false}""",
            request.body,
        )
        val parsed = JSONObject(request.body!!)
        assertEquals("Tom's \"Game\" & Co", parsed.getString("app"))
        assertEquals(42, parsed.getInt("seconds"))
        assertEquals(listOf("Content-Type" to "application/json"), request.headers)
    }

    @Test
    fun nonNumericNumberFieldIsSentAsText() {
        val body = RequestBuilder.jsonBody(listOf(BodyField("n", "{{app}}", FieldType.NUMBER)), vars)
        assertEquals("Tom's \"Game\" & Co", JSONObject(body!!).getString("n"))
    }

    @Test
    fun rowsWithoutKeyAreSkippedAndEmptyBodyIsNull() {
        assertNull(RequestBuilder.jsonBody(listOf(BodyField(" ", "x")), vars))
        assertNull(RequestBuilder.build(rule(fields = emptyList()), vars).body)
    }

    @Test
    fun formBodyIsUrlEncoded() {
        val request = RequestBuilder.build(
            rule(bodyType = BodyType.FORM, fields = listOf(BodyField("app", "{{app}}"), BodyField("e", "{{event}}"))),
            vars,
        )
        assertEquals("app=Tom%27s+%22Game%22+%26+Co&e=closed", request.body)
        assertEquals(listOf("Content-Type" to "application/x-www-form-urlencoded"), request.headers)
    }

    @Test
    fun textBodyIsSentVerbatimWithPlaceholders() {
        val request = RequestBuilder.build(rule(bodyType = BodyType.TEXT, body = "{{event}}: {{app}}"), vars)
        assertEquals("closed: Tom's \"Game\" & Co", request.body)
        assertEquals(listOf("Content-Type" to "text/plain; charset=utf-8"), request.headers)
    }

    @Test
    fun textBodyThatLooksLikeJsonIsEscapedAsJson() {
        val request = RequestBuilder.build(
            rule(bodyType = BodyType.TEXT, body = """{"nested": {"app": "{{app}}"}}"""), vars
        )
        assertEquals("Tom's \"Game\" & Co", JSONObject(request.body!!).getJSONObject("nested").getString("app"))
        assertEquals(listOf("Content-Type" to "application/json"), request.headers)
    }

    @Test
    fun userContentTypeIsKept() {
        val request = RequestBuilder.build(
            rule(fields = listOf(BodyField("a", "b")), headers = listOf(KeyValue("content-type", "application/vnd.x+json"))),
            vars,
        )
        assertEquals(listOf("content-type" to "application/vnd.x+json"), request.headers)
    }

    @Test
    fun headersAreRenderedAndKeptOnOneLine() {
        val request = RequestBuilder.build(
            rule(method = "GET", headers = listOf(KeyValue("X-App", "{{app}}\nX-Evil: 1"), KeyValue("", "dropped"))),
            vars,
        )
        assertEquals(listOf("X-App" to "Tom's \"Game\" & Co X-Evil: 1"), request.headers)
    }

    @Test
    fun getSendsNoBody() {
        val request = RequestBuilder.build(rule(method = "get", fields = listOf(BodyField("a", "b"))), vars)
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals(emptyList<Pair<String, String>>(), request.headers)
    }

    @Test
    fun urlPlaceholdersAreUrlEncoded() {
        assertEquals(
            "https://h.test/hook?app=Tom%27s%20%22Game%22%20%26%20Co",
            RequestBuilder.build(rule(method = "GET"), vars).url,
        )
    }
}
