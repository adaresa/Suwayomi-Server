package xyz.nulldev.androidcompat.webkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class KcefEvaluationTest {
    @Test
    fun completionValuesAreJsonEncodedLikeAndroid() {
        assertEquals("\"a\\\"b\"", androidEvaluationResult("""{"result":{"type":"string","value":"a\"b"}}"""))
        assertEquals("{\"page\":[1,2]}", androidEvaluationResult("""{"result":{"type":"object","value":{"page":[1,2]}}}"""))
        assertEquals("42", androidEvaluationResult("""{"result":{"type":"number","value":42,"description":"42"}}"""))
    }

    @Test
    fun missingValuesAndErrorsReportNull() {
        assertEquals("null", androidEvaluationResult("""{"result":{"type":"undefined"}}"""))
        assertEquals("null", androidEvaluationResult("""{"result":{"type":"object","subtype":"null","value":null}}"""))
        assertEquals(
            "null",
            androidEvaluationResult("""{"result":{"type":"object","value":{}},"exceptionDetails":{"text":"Uncaught"}}"""),
        )
        assertEquals("null", androidEvaluationResult(null))
    }

    @Test
    fun userAgentOverrideReportsTheSameBrowserInItsClientHints() {
        val android = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        val override = Json.parseToJsonElement(userAgentOverride(android)).jsonObject
        assertEquals(android, override.getValue("userAgent").jsonPrimitive.content)
        val hints = override.getValue("userAgentMetadata").jsonObject
        assertEquals("Android", hints.getValue("platform").jsonPrimitive.content)
        assertEquals("true", hints.getValue("mobile").jsonPrimitive.content)
        assertEquals("149.0.7827.197", hints.getValue("fullVersion").jsonPrimitive.content)

        val linux = Json.parseToJsonElement(userAgentOverride("Mozilla/5.0 (X11; Linux x86_64) Chrome/152.0.0.0 Safari/537.36"))
        val linuxHints = linux.jsonObject.getValue("userAgentMetadata").jsonObject
        assertEquals("Linux", linuxHints.getValue("platform").jsonPrimitive.content)
        assertEquals("false", linuxHints.getValue("mobile").jsonPrimitive.content)

        assertEquals(setOf("userAgent"), Json.parseToJsonElement(userAgentOverride("curl/8")).jsonObject.keys)
    }
}
