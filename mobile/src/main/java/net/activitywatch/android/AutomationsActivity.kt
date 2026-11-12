package net.activitywatch.android

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import net.activitywatch.android.automation.AutomationStore
import net.activitywatch.android.watcher.WebWatcher

/**
 * Lists the user's app-session automations, lets them switch each on/off, and sets how
 * the foreground app is detected.
 */
class AutomationsActivity : AppCompatActivity() {

    private lateinit var store: AutomationStore
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var fastPathStatus: TextView
    private lateinit var fastPathButton: Button

    // Last results are written from the dispatcher thread while this screen is open.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { renderRules() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automations)
        applySafeWindowInsets(followNightMode = true)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.automations)
        }

        store = AutomationStore(this)
        listContainer = findViewById(R.id.automations_list)
        emptyView = findViewById(R.id.automations_empty)
        fastPathStatus = findViewById(R.id.tv_fast_path_status)
        fastPathButton = findViewById(R.id.btn_enable_fast_path)

        findViewById<Button>(R.id.btn_add_automation).setOnClickListener {
            startActivity(Intent(this, AutomationEditActivity::class.java))
        }
        fastPathButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setUpPollIntervalSpinner()
    }

    override fun onStart() {
        super.onStart()
        store.registerListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        renderRules()
        renderFastPathStatus()
        ensureBackgroundServiceRunning()
    }

    override fun onStop() {
        store.unregisterListener(prefsListener)
        super.onStop()
    }

    private fun setUpPollIntervalSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_poll_interval)
        val choices = AutomationStore.POLL_INTERVAL_CHOICES
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.second })
        val current = store.getPollIntervalMs()
        spinner.setSelection(choices.indexOfFirst { it.first == current }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val ms = choices[position].first
                if (ms != store.getPollIntervalMs()) store.setPollIntervalMs(ms)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun renderRules() {
        val rules = store.getRules()
        listContainer.removeAllViews()
        emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (rule in rules) {
            val row = inflater.inflate(R.layout.item_automation, listContainer, false)
            row.findViewById<TextView>(R.id.tv_automation_name).text =
                rule.name.ifBlank { getString(R.string.automations) }
            row.findViewById<TextView>(R.id.tv_automation_summary).text = rule.summary()
            row.findViewById<TextView>(R.id.tv_automation_last_result).text =
                store.getLastResult(rule.id)?.let { result ->
                    val ago = DateUtils.getRelativeTimeSpanString(
                        result.at, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                    )
                    "${if (result.success) "✓" else "✗"} ${result.message} · $ago"
                } ?: getString(R.string.automations_never_run)
            val toggle = row.findViewById<SwitchCompat>(R.id.switch_automation_enabled)
            toggle.isChecked = rule.enabled
            toggle.setOnCheckedChangeListener { _, checked -> store.setEnabled(rule.id, checked) }
            row.setOnClickListener {
                startActivity(
                    Intent(this, AutomationEditActivity::class.java)
                        .putExtra(AutomationEditActivity.EXTRA_RULE_ID, rule.id)
                )
            }
            listContainer.addView(row)
        }
    }

    private fun renderFastPathStatus() {
        val on = isAccessibilityServiceEnabled()
        fastPathStatus.setText(if (on) R.string.automations_fast_path_on else R.string.automations_fast_path_off)
        fastPathButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(this, WebWatcher::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    // The monitor lives in BackgroundService. MainActivity normally starts it, but make
    // sure it is up so a newly enabled automation starts working right away.
    private fun ensureBackgroundServiceRunning() {
        if (!store.hasEnabledRules()) return
        try {
            startForegroundService(
                Intent(this, BackgroundService::class.java)
                    .putExtra(BackgroundService.EXTRA_START_ORIGIN, BackgroundService.START_ORIGIN_SETTINGS)
            )
        } catch (e: Exception) {
            android.util.Log.w("AutomationsActivity", "Could not start BackgroundService", e)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
