package net.activitywatch.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.activitywatch.android.AutomationEditorForm.TargetOption
import net.activitywatch.android.automation.AutomationDispatcher
import net.activitywatch.android.automation.AutomationRule
import net.activitywatch.android.automation.AutomationStore
import net.activitywatch.android.automation.AwCategories
import net.activitywatch.android.automation.HttpActionRunner
import net.activitywatch.android.automation.SessionTransition
import net.activitywatch.android.automation.TargetType
import net.activitywatch.android.automation.TriggerType
import org.json.JSONArray
import kotlin.concurrent.thread

private const val TAG = "AutomationEditActivity"

/** Create or edit one WHEN-app-session / DO-get-contents-of-URL automation. */
class AutomationEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE_ID = "net.activitywatch.android.extra.AUTOMATION_RULE_ID"
    }

    private lateinit var store: AutomationStore
    private lateinit var form: AutomationEditorForm
    private var original: AutomationRule? = null
    private var awCategories: AwCategories? = null

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

        val root = findViewById<View>(R.id.automation_scroll)
        form = AutomationEditorForm(this, root, original)
        loadAppsAsync()
        loadAwCategoriesAsync()

        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_test).setOnClickListener { test() }
        findViewById<View>(R.id.btn_delete).apply {
            visibility = if (original == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
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
                    .map { pkg -> TargetOption(pkg, appLabel(pkg)) }
                    .toMutableList()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list apps", e)
                mutableListOf()
            }
            // Keep a previously chosen app selectable even if it has no launcher icon.
            original?.takeIf { it.targetType == TargetType.APP && it.targetValue.isNotEmpty() }?.let { rule ->
                if (apps.none { it.value == rule.targetValue }) {
                    apps += TargetOption(rule.targetValue, rule.targetLabel.ifEmpty { rule.targetValue })
                }
            }
            apps.sortBy { it.label.lowercase() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) form.setAppOptions(apps)
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
                TargetOption(JSONArray(path).toString(), AwCategories.formatPath(path))
            }.toMutableList()
            // Keep a previously chosen category selectable even if it was since renamed.
            original?.takeIf { it.targetType == TargetType.AW_CATEGORY }?.let { rule ->
                if (rule.targetValue.isNotEmpty() && options.none { it.value == rule.targetValue }) {
                    options += TargetOption(rule.targetValue, "${rule.targetLabel} (no longer defined)")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                awCategories = categories
                form.setAwCategoryOptions(options)
            }
        }
    }

    private fun save() {
        val rule = form.read() ?: return
        store.saveRule(rule)
        Toast.makeText(this, form.cleartextNotice(rule.url) ?: "Automation saved", Toast.LENGTH_LONG).show()
        finish()
    }

    /** Sends the request once, now, with sample values for the chosen target. */
    private fun test() {
        val rule = form.read()?.copy(enabled = true) ?: return
        form.showTestResult(getString(R.string.automation_testing))
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
                form.showTestResult(
                    "${if (result.success) "✓" else "✗"} Test (${vars["event"]} ${app.label}): ${result.message}"
                )
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
