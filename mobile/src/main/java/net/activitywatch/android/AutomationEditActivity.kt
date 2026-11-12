package net.activitywatch.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.activitywatch.android.automation.AutomationDispatcher
import net.activitywatch.android.automation.AutomationRule
import net.activitywatch.android.automation.AutomationStore
import net.activitywatch.android.automation.AwCategories
import net.activitywatch.android.automation.HttpActionRunner
import net.activitywatch.android.automation.LocalNetwork
import net.activitywatch.android.automation.RequestTemplate
import net.activitywatch.android.automation.SessionTransition
import net.activitywatch.android.automation.TargetType
import net.activitywatch.android.automation.TriggerType
import org.json.JSONArray
import kotlin.concurrent.thread

private const val TAG = "AutomationEditActivity"

/** Create or edit one IF-app-session / THEN-HTTP-request automation. */
class AutomationEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE_ID = "net.activitywatch.android.extra.AUTOMATION_RULE_ID"
    }

    /** One entry of the target dropdown: stored value + what the user sees. */
    private data class Option(val value: String, val label: String) {
        override fun toString() = label
    }

    private lateinit var store: AutomationStore
    private var original: AutomationRule? = null

    private lateinit var etName: EditText
    private lateinit var spinnerTrigger: Spinner
    private lateinit var spinnerTargetType: Spinner
    private lateinit var tvTargetValueLabel: TextView
    private lateinit var spinnerTargetValue: Spinner
    private lateinit var tvTargetHint: TextView
    private lateinit var rowMinDuration: View
    private lateinit var etMinDuration: EditText
    private lateinit var spinnerMethod: Spinner
    private lateinit var etUrl: EditText
    private lateinit var etHeaders: EditText
    private lateinit var etBody: EditText
    private lateinit var tvTestResult: TextView

    // Loaded off the main thread; null until loaded.
    private var appOptions: List<Option>? = null
    private var awCategoryOptions: List<Option>? = null
    private var awCategories: AwCategories? = null

    // The target the form currently has selected per type, so switching the type
    // dropdown back and forth does not lose the choice.
    private val selectedTarget = mutableMapOf<TargetType, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automation_edit)
        applySafeWindowInsets(followNightMode = true)

        store = AutomationStore(this)
        original = intent.getStringExtra(EXTRA_RULE_ID)?.let(store::getRule)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(if (original == null) R.string.automation_new else R.string.automation_edit)
        }

        etName = findViewById(R.id.et_name)
        spinnerTrigger = findViewById(R.id.spinner_trigger)
        spinnerTargetType = findViewById(R.id.spinner_target_type)
        tvTargetValueLabel = findViewById(R.id.tv_target_value_label)
        spinnerTargetValue = findViewById(R.id.spinner_target_value)
        tvTargetHint = findViewById(R.id.tv_target_hint)
        rowMinDuration = findViewById(R.id.row_min_duration)
        etMinDuration = findViewById(R.id.et_min_duration)
        spinnerMethod = findViewById(R.id.spinner_method)
        etUrl = findViewById(R.id.et_url)
        etHeaders = findViewById(R.id.et_headers)
        etBody = findViewById(R.id.et_body)
        tvTestResult = findViewById(R.id.tv_test_result)

        findViewById<TextView>(R.id.tv_placeholders).text = buildString {
            append(getString(R.string.automation_placeholders))
            RequestTemplate.PLACEHOLDERS.forEach { (name, desc) -> append("\n{{$name}} — $desc") }
        }

        val rule = original ?: AutomationRule()
        selectedTarget[rule.targetType] = rule.targetValue

        spinnerTrigger.adapter = simpleAdapter(TriggerType.entries.map { it.label })
        spinnerTrigger.setSelection(rule.trigger.ordinal, false)
        spinnerTrigger.onItemSelectedListener = onSelected { updateMinDurationVisibility() }

        spinnerTargetType.adapter = simpleAdapter(TargetType.entries.map { it.label })
        spinnerTargetType.setSelection(rule.targetType.ordinal, false)
        spinnerTargetType.onItemSelectedListener = onSelected { renderTargetValues() }

        spinnerTargetValue.onItemSelectedListener = onSelected { position ->
            val option = spinnerTargetValue.adapter.getItem(position) as? Option ?: return@onSelected
            // The "Loading…" placeholder must not overwrite the saved choice.
            if (option.value.isNotEmpty()) selectedTarget[currentTargetType()] = option.value
        }

        spinnerMethod.adapter = simpleAdapter(AutomationRule.METHODS)
        spinnerMethod.setSelection(AutomationRule.METHODS.indexOf(rule.method).coerceAtLeast(0), false)

        etName.setText(rule.name)
        etMinDuration.setText(if (rule.minDurationSeconds > 0) rule.minDurationSeconds.toString() else "")
        etUrl.setText(rule.url)
        etHeaders.setText(rule.headers)
        etBody.setText(rule.body)

        updateMinDurationVisibility()
        renderTargetValues()
        loadAppsAsync()
        loadAwCategoriesAsync()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_delete).apply {
            visibility = if (original == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
    }

    private fun currentTargetType() = TargetType.entries[spinnerTargetType.selectedItemPosition]
    private fun currentTrigger() = TriggerType.entries[spinnerTrigger.selectedItemPosition]

    private fun updateMinDurationVisibility() {
        rowMinDuration.visibility = if (currentTrigger() == TriggerType.OPENED) View.GONE else View.VISIBLE
    }

    // --- Target dropdown --------------------------------------------------------------

    private fun renderTargetValues() {
        val type = currentTargetType()
        tvTargetHint.visibility = View.GONE
        if (type == TargetType.ANY) {
            tvTargetValueLabel.visibility = View.GONE
            spinnerTargetValue.visibility = View.GONE
            return
        }
        tvTargetValueLabel.visibility = View.VISIBLE
        spinnerTargetValue.visibility = View.VISIBLE
        tvTargetValueLabel.setText(
            when (type) {
                TargetType.APP -> R.string.automation_target_app_label
                TargetType.ANDROID_CATEGORY -> R.string.automation_target_android_category_label
                else -> R.string.automation_target_aw_category_label
            }
        )
        val options: List<Option> = when (type) {
            TargetType.APP -> appOptions ?: placeholderOption("Loading apps…")
            TargetType.ANDROID_CATEGORY -> androidCategoryOptions().also {
                showHint(getString(R.string.automation_android_category_hint))
            }
            TargetType.AW_CATEGORY -> awCategoryOptions?.also {
                if (it.isEmpty()) showHint(getString(R.string.automation_aw_categories_none))
            } ?: placeholderOption(getString(R.string.automation_aw_categories_loading))
            TargetType.ANY -> emptyList()
        }
        spinnerTargetValue.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val wanted = selectedTarget[type]
        val idx = options.indexOfFirst { it.value == wanted }
        if (idx >= 0) {
            spinnerTargetValue.setSelection(idx, false)
        } else if (options.isNotEmpty() && options[0].value.isNotEmpty()) {
            selectedTarget[type] = options[0].value
        }
    }

    private fun placeholderOption(label: String) = listOf(Option("", label))

    private fun showHint(text: String) {
        tvTargetHint.text = text
        tvTargetHint.visibility = View.VISIBLE
    }

    private fun androidCategoryOptions(): List<Option> {
        val categories = mutableListOf(
            ApplicationInfo.CATEGORY_GAME,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_SOCIAL,
            ApplicationInfo.CATEGORY_NEWS,
            ApplicationInfo.CATEGORY_MAPS,
            ApplicationInfo.CATEGORY_PRODUCTIVITY,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) categories += ApplicationInfo.CATEGORY_ACCESSIBILITY
        return categories.map {
            Option(it.toString(), AutomationDispatcher.androidCategoryLabel(this, it).ifEmpty { "Category $it" })
        }
    }

    private fun loadAppsAsync() {
        thread(name = "Automation-apps") {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = try {
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .map { pkg -> Option(pkg, appLabel(pkg)) }
                    .toMutableList()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list apps", e)
                mutableListOf()
            }
            // Keep a previously chosen app selectable even if it has no launcher icon.
            original?.takeIf { it.targetType == TargetType.APP && it.targetValue.isNotEmpty() }?.let { rule ->
                if (apps.none { it.value == rule.targetValue }) {
                    apps += Option(rule.targetValue, rule.targetLabel.ifEmpty { rule.targetValue })
                }
            }
            apps.sortBy { it.label.lowercase() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appOptions = apps
                if (currentTargetType() == TargetType.APP) renderTargetValues()
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun loadAwCategoriesAsync() {
        thread(name = "Automation-categories") {
            val categories = try {
                AwCategories.parse(RustInterface(applicationContext).getSetting("classes"))
            } catch (e: Throwable) {
                Log.w(TAG, "Could not read ActivityWatch categories", e)
                null
            }
            val options = categories?.allPaths().orEmpty().map { path ->
                Option(JSONArray(path).toString(), AwCategories.formatPath(path))
            }.toMutableList()
            // Keep a previously chosen category selectable even if it was since renamed.
            original?.takeIf { it.targetType == TargetType.AW_CATEGORY }?.let { rule ->
                if (rule.targetValue.isNotEmpty() && options.none { it.value == rule.targetValue }) {
                    options += Option(rule.targetValue, "${rule.targetLabel} (no longer defined)")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                awCategories = categories
                awCategoryOptions = options
                if (currentTargetType() == TargetType.AW_CATEGORY) renderTargetValues()
            }
        }
    }

    // --- Save / test / delete ---------------------------------------------------------

    /** Reads the form into a rule, or shows what's wrong and returns null. */
    private fun ruleFromForm(): AutomationRule? {
        val type = currentTargetType()
        val targetValue = if (type == TargetType.ANY) "" else selectedTarget[type].orEmpty()
        if (type != TargetType.ANY && targetValue.isEmpty()) {
            Toast.makeText(this, "Choose which ${tvTargetValueLabel.text.toString().lowercase()} this applies to", Toast.LENGTH_SHORT).show()
            return null
        }
        val url = etUrl.text.toString().trim()
        val urlError = validateUrl(url)
        if (urlError != null) {
            etUrl.error = urlError
            etUrl.requestFocus()
            return null
        }
        val targetLabel = (0 until (spinnerTargetValue.adapter?.count ?: 0))
            .mapNotNull { spinnerTargetValue.adapter.getItem(it) as? Option }
            .firstOrNull { it.value == targetValue }?.label.orEmpty()
        return (original ?: AutomationRule()).copy(
            name = etName.text.toString().trim().ifEmpty { defaultName(type, targetLabel) },
            trigger = currentTrigger(),
            targetType = type,
            targetValue = targetValue,
            targetLabel = if (type == TargetType.ANY) "" else targetLabel,
            minDurationSeconds = etMinDuration.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            method = AutomationRule.METHODS[spinnerMethod.selectedItemPosition],
            url = url,
            headers = etHeaders.text.toString(),
            body = etBody.text.toString(),
        )
    }

    private fun defaultName(type: TargetType, targetLabel: String): String {
        val what = if (type == TargetType.ANY) "Any app" else targetLabel
        val edge = when (currentTrigger()) {
            TriggerType.OPENED -> "opened"
            TriggerType.CLOSED -> "closed"
            TriggerType.EITHER -> "opened/closed"
        }
        return "$what $edge"
    }

    private fun validateUrl(url: String): String? {
        if (url.isEmpty()) return "Enter the URL to call"
        // Placeholders are allowed in the URL; validate with sample values substituted.
        val probe = Uri.parse(url.replace(Regex("""\{\{[^}]*\}\}"""), "x"))
        val scheme = probe.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return "URL must start with https:// or http://"
        if (probe.host.isNullOrEmpty()) return "URL is missing a host"
        // Like iOS Shortcuts: https:// anywhere, plain http:// on the local network only.
        // The send-time check on the resolved address is authoritative (HttpActionRunner).
        if (scheme == "http" && LocalNetwork.classifyHost(probe.host!!) == LocalNetwork.HostKind.PUBLIC) {
            return "Plain http:// only works on your local network. Use https:// for internet addresses."
        }
        return null
    }

    /** A note for http:// names we can't classify until they are resolved at send time. */
    private fun cleartextNotice(url: String): String? {
        val probe = Uri.parse(url.replace(Regex("""\{\{[^}]*\}\}"""), "x"))
        if (probe.scheme?.lowercase() != "http") return null
        val host = probe.host ?: return null
        if (LocalNetwork.classifyHost(host) != LocalNetwork.HostKind.UNKNOWN) return null
        return "Plain http:// requests to $host are only sent if it resolves to a local-network address."
    }

    private fun save() {
        val rule = ruleFromForm() ?: return
        store.saveRule(rule)
        Toast.makeText(this, cleartextNotice(rule.url) ?: "Automation saved", Toast.LENGTH_LONG).show()
        finish()
    }

    /** Sends the request once, now, with sample values for the chosen target. */
    private fun test() {
        val rule = ruleFromForm()?.copy(enabled = true) ?: return
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.setText(R.string.automation_testing)
        val categories = awCategories
        thread(name = "Automation-test") {
            val pkg = if (rule.targetType == TargetType.APP) rule.targetValue else packageName
            val app = AutomationDispatcher.describeApp(applicationContext, pkg, categories)
            val now = System.currentTimeMillis()
            val transition = if (rule.trigger == TriggerType.CLOSED) {
                SessionTransition.Closed(pkg, now - 60_000L, now)
            } else {
                SessionTransition.Opened(pkg, now)
            }
            val vars = AutomationDispatcher.templateVars(applicationContext, rule, transition, app)
            val result = HttpActionRunner.execute(rule, vars)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvTestResult.text = "${if (result.success) "✓" else "✗"} Test (${vars["event"]} ${app.label}): ${result.message}"
            }
        }
    }

    private fun confirmDelete() {
        val rule = original ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete automation?")
            .setMessage(rule.name)
            .setPositiveButton(R.string.automation_delete) { _, _ ->
                store.deleteRule(rule.id)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Helpers ----------------------------------------------------------------------

    private fun simpleAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun onSelected(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
