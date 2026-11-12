package net.activitywatch.android.automation

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import net.activitywatch.android.deviceHostname
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "AutomationDispatcher"

/**
 * Runs the THEN part: for each session transition, finds the enabled rules it matches and
 * sends their HTTP requests. Requests go out one at a time on a background thread, so an
 * "opened" call always leaves before the "closed" call that follows it.
 *
 * [categoriesSetting] returns the raw aw-webui `classes` setting; it is called on the
 * dispatcher thread (it may block on the datastore) and cached for [CATEGORY_CACHE_MS].
 */
class AutomationDispatcher(
    context: Context,
    private val store: AutomationStore,
    private val categoriesSetting: () -> String?,
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Automation-http")
    }

    private var cachedCategories: AwCategories? = null
    private var categoriesLoadedAt = 0L

    fun dispatch(transition: SessionTransition) {
        executor.execute {
            try {
                handle(transition)
            } catch (e: Exception) {
                Log.e(TAG, "Automation dispatch failed for $transition", e)
            }
        }
    }

    fun shutdown() = executor.shutdown()

    private fun handle(transition: SessionTransition) {
        val rules = store.getRules().filter { it.enabled }
        if (rules.isEmpty()) return
        // Categorizing may hit the datastore, so only do it when a rule matches on it or
        // its request mentions {{category}}.
        val wantsCategory = rules.any { r ->
            r.targetType == TargetType.AW_CATEGORY ||
                listOf(r.url, r.headers, r.body).any { "category" in it }
        }
        val app = describeApp(appContext, transition.packageName, if (wantsCategory) categories() else null)
        for (rule in rules) {
            if (!rule.matches(transition, app)) continue
            Log.i(TAG, "Rule '${rule.name}' matched ${transition::class.simpleName} ${app.packageName}")
            val result = HttpActionRunner.execute(rule, templateVars(appContext, rule, transition, app))
            store.setLastResult(rule.id, result)
        }
    }

    private fun categories(): AwCategories? {
        val now = SystemClock.elapsedRealtime()
        if (categoriesLoadedAt == 0L || now - categoriesLoadedAt > CATEGORY_CACHE_MS) {
            cachedCategories = try {
                AwCategories.parse(categoriesSetting())
            } catch (e: Throwable) {
                Log.w(TAG, "Could not load ActivityWatch categories", e)
                cachedCategories
            }
            categoriesLoadedAt = now
        }
        return cachedCategories
    }

    companion object {
        private const val CATEGORY_CACHE_MS = 5 * 60 * 1000L

        fun describeApp(context: Context, packageName: String, categories: AwCategories?): SessionApp {
            val pm = context.packageManager
            val info: ApplicationInfo? = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val label = info?.let { pm.getApplicationLabel(it).toString() } ?: packageName
            val androidCategory = info?.category ?: ApplicationInfo.CATEGORY_UNDEFINED
            return SessionApp(
                packageName = packageName,
                label = label,
                androidCategory = androidCategory,
                androidCategoryLabel = androidCategoryLabel(context, androidCategory),
                awCategory = categories?.classify(label, packageName),
            )
        }

        fun androidCategoryLabel(context: Context, category: Int): String =
            if (category == ApplicationInfo.CATEGORY_UNDEFINED) "" else
                ApplicationInfo.getCategoryTitle(context, category)?.toString() ?: ""

        fun templateVars(
            context: Context,
            rule: AutomationRule,
            transition: SessionTransition,
            app: SessionApp,
        ): Map<String, String> {
            val (event, start, duration) = when (transition) {
                is SessionTransition.Opened -> Triple("opened", transition.at, 0L)
                is SessionTransition.Closed -> Triple("closed", transition.startedAt, transition.durationMs / 1000)
            }
            return mapOf(
                "event" to event,
                "app" to app.label,
                "package" to app.packageName,
                "category" to (app.awCategory?.let(AwCategories::formatPath) ?: "Uncategorized"),
                "android_category" to app.androidCategoryLabel,
                "timestamp" to Instant.ofEpochMilli(transition.at).toString(),
                "epoch_ms" to transition.at.toString(),
                "session_start" to Instant.ofEpochMilli(start).toString(),
                "duration_seconds" to duration.toString(),
                "rule" to rule.name,
                "device" to deviceHostname(context),
            )
        }
    }
}

/** Builds and sends one rule's request. Blocking; call off the main thread. */
object HttpActionRunner {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val RETRY_DELAY_MS = 2_000L

    fun execute(rule: AutomationRule, vars: Map<String, String>): AutomationStore.RunResult {
        val url = RequestTemplate.render(rule.url.trim(), vars, RequestTemplate.Escape.URL)
        val headers = RequestTemplate.parseHeaders(rule.headers).map { (k, v) ->
            k to RequestTemplate.render(v, vars, RequestTemplate.Escape.NONE).replace(Regex("[\r\n]"), " ")
        }
        val body = RequestTemplate.render(rule.body, vars, RequestTemplate.bodyEscape(headers, rule.body))
        val method = rule.method.uppercase()

        // One retry covers a request fired just as the network comes back (e.g. on unlock).
        var lastError: Exception? = null
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            try {
                val code = send(method, url, headers, body)
                val ok = code in 200..299
                Log.i("HttpActionRunner", "${rule.name}: $method $url -> HTTP $code")
                return AutomationStore.RunResult(System.currentTimeMillis(), ok, "HTTP $code")
            } catch (e: IOException) {
                lastError = e
                Log.w("HttpActionRunner", "${rule.name}: $method $url failed (attempt ${attempt + 1})", e)
            } catch (e: Exception) {
                // Malformed URL, unsupported method, cleartext blocked, ...: retrying won't help.
                Log.w("HttpActionRunner", "${rule.name}: $method $url failed", e)
                return AutomationStore.RunResult(System.currentTimeMillis(), false, "Failed: ${describe(e)}")
            }
        }
        return AutomationStore.RunResult(System.currentTimeMillis(), false, "Failed: ${describe(lastError)}")
    }

    private fun send(method: String, url: String, headers: List<Pair<String, String>>, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            // HttpURLConnection silently turns a GET with a body into a POST, so GET never sends one.
            if (method != "GET" && body.isNotBlank()) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            // Drain so the connection can be reused.
            try {
                (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() }
            } catch (_: IOException) {
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Exception?): String =
        e?.message?.takeIf { it.isNotBlank() } ?: e?.javaClass?.simpleName ?: "unknown error"
}
