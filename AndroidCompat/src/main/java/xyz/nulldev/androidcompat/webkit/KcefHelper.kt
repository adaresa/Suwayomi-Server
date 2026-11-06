package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private val logger = KotlinLogging.logger {}
private val jsHandler: MutableMap<CefClient, JsHandler> = Collections.synchronizedMap(WeakHashMap())

fun CefBrowser.evaluateJavaScript(
    expression: String,
    cb: (String?) -> Unit,
) = jsHandler[this.client]!!.eval(this, expression, cb)

/**
 * Evaluates [script] in the main frame with Android's `WebView.evaluateJavascript` semantics:
 * any script reports its completion value JSON-encoded, and "null" when it is undefined, the
 * script throws, or the browser is not open. Like Android's, this does not wait for a promise and
 * is exempt from the page's Content Security Policy.
 */
fun CefBrowser.evaluateJavaScriptLikeAndroid(
    script: String,
    cb: (String) -> Unit,
) {
    val params =
        buildJsonObject {
            put("expression", script)
            put("returnByValue", true)
        }
    // There is no client until the browser has been created, or once it is closing
    val evaluation = devToolsClient?.executeDevToolsMethod("Runtime.evaluate", params.toString())
    if (evaluation == null) {
        cb("null")
        return
    }
    evaluation
        .handle { response, error ->
            if (error != null) logger.debug(error) { "JavaScript evaluation failed" }
            androidEvaluationResult(response)
        }.thenAccept { cb(it) }
}

/** Converts a DevTools `Runtime.evaluate` response to the value Android passes to its callback. */
internal fun androidEvaluationResult(response: String?): String =
    runCatching {
        val evaluation = Json.parseToJsonElement(response!!).jsonObject
        evaluation["result"]
            ?.jsonObject
            ?.get("value")
            ?.takeUnless { "exceptionDetails" in evaluation }
            ?.toString()
    }.getOrNull() ?: "null"

/**
 * DevTools `Emulation.setUserAgentOverride` params that make the page, its requests and its client
 * hints all report [userAgent]. Sites' bot checks compare them, so setting only the header fails.
 */
internal fun userAgentOverride(userAgent: String): String =
    buildJsonObject {
        put("userAgent", userAgent)
        val version = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.get(1) ?: return@buildJsonObject
        val android = "Android" in userAgent
        val platform =
            when {
                android -> "Android"
                "Windows" in userAgent -> "Windows"
                "Mac OS X" in userAgent -> "macOS"
                else -> "Linux"
            }
        val fullVersion = if (version == "149") "149.0.7827.197" else "$version.0.0.0"

        fun JsonArrayBuilder.brand(
            name: String,
            brandVersion: String,
        ) = addJsonObject {
            put("brand", name)
            put("version", brandVersion)
        }
        putJsonObject("userAgentMetadata") {
            putJsonArray("brands") {
                brand("Chromium", version)
                brand("Not)A;Brand", "24")
            }
            putJsonArray("fullVersionList") {
                brand("Chromium", fullVersion)
                brand("Not)A;Brand", "24.0.0.0")
            }
            putJsonArray("formFactors") { add(if (android) "Mobile" else "Desktop") }
            put("fullVersion", fullVersion)
            put("platform", platform)
            put(
                "platformVersion",
                when (platform) {
                    "Android" -> "15.0.0"
                    "Windows" -> "19.0.0"
                    else -> ""
                },
            )
            put("architecture", if (android) "arm" else "x86")
            put("model", if (android) "2312DRAABG" else "")
            put("mobile", android)
            put("bitness", "")
            put("wow64", false)
        }
    }.toString()

fun CefBrowser.dispose() {
    stopLoad()
    // Before the close: JCEF cancels a close it hasn't allowed and ignores any later close
    setCloseAllowed()
    close(true)
}

fun CefClient.disposeWithJsHandler() {
    jsHandler.remove(this)
    dispose()
}

class JsHandler : CefMessageRouterHandlerAdapter {
    private val handler = ConcurrentHashMap<String, (String?) -> Unit>()

    constructor(client: CefClient) {
        val config = CefMessageRouter.CefMessageRouterConfig()
        config.jsQueryFunction = QUERY_FN
        config.jsCancelFunction = QUERY_CANCEL_FN
        client.addMessageRouter(CefMessageRouter.create(config, this))
        jsHandler[client] = this
    }

    fun eval(
        frame: CefFrame,
        expression: String,
        cb: (String?) -> Unit,
    ) {
        val id = Random.nextBytes(48).toHexString()
        handler[id] = cb
        frame.executeJavaScript(expression.toCode(id), "about:cef", 0)
    }

    fun eval(
        browser: CefBrowser,
        expression: String,
        cb: (String?) -> Unit,
    ) {
        val id = Random.nextBytes(48).toHexString()
        handler[id] = cb
        browser.executeJavaScript(expression.toCode(id), "about:cef", 0)
    }

    override fun onQuery(
        browser: CefBrowser?,
        frame: CefFrame?,
        queryId: Long,
        request: String?,
        persistent: Boolean,
        callback: CefQueryCallback?,
    ): Boolean {
        super.onQuery(browser, frame, queryId, request, persistent, callback)

        if (request != null) {
            val invoke =
                try {
                    Json.decodeFromString<FunctionCall>(request)
                } catch (e: Exception) {
                    logger.warn(e) { "Invalid request received" }
                    return false
                }
            val handler = handler.remove(invoke.id) ?: return false
            handler(invoke.result)
            callback?.success("")
            return true
        }

        return false
    }

    @Serializable
    private data class FunctionCall(
        val id: String,
        val result: String? = null,
    )

    companion object {
        const val QUERY_FN = "__\$_evalQuery"
        const val QUERY_CANCEL_FN = "__\$_evalQueryCancel"

        private fun Char.isLineBreak(): Boolean = this == '\n' || this == '\r'

        private fun String.containsLineBreak(): Boolean =
            this.any {
                it.isLineBreak()
            }

        private fun String.asFunctionBody(): String =
            let { expression ->
                when {
                    expression.containsLineBreak() -> expression
                    expression.trim().startsWith("return", false) -> expression
                    else -> "return $expression"
                }
            }

        private fun String.toCode(id: String): String =
            """
            function payload() {
              ${this.asFunctionBody()}
            }

            try {
              var result = payload();

              window.${QUERY_FN}({
                  request: JSON.stringify({ id: "$id", result }),
                  onSuccess: function (response) {},
                  onFailure: function (error_code, error_message) {}
              });
            } catch (e) {
              console.error("Failed to eval $id", e)
              window.${QUERY_CANCEL_FN}({
                  request: JSON.stringify({ id: "$id", error: ""+e }),
                  onSuccess: function (response) {},
                  onFailure: function (error_code, error_message) {}
              });
            }
            """.trimIndent()
    }
}
