package net.activitywatch.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import net.activitywatch.android.automation.AutomationDispatcher
import net.activitywatch.android.automation.AutomationRule
import net.activitywatch.android.automation.BodyField
import net.activitywatch.android.automation.BodyType
import net.activitywatch.android.automation.FieldType
import net.activitywatch.android.automation.KeyValue
import net.activitywatch.android.automation.LocalNetwork
import net.activitywatch.android.automation.RequestTemplate
import net.activitywatch.android.automation.TargetType
import net.activitywatch.android.automation.TriggerType

/**
 * The automation editor's form, laid out like a Shortcuts automation: a WHEN card, a
 * "Get contents of URL" action card with header rows and a JSON / Form / Text body, and
 * variable chips that insert `{{placeholders}}` into the field being edited.
 *
 * Plain framework views only, so the same code drives [AutomationEditActivity] and the
 * layout preview harness.
 */
class AutomationEditorForm(
    private val context: Context,
    private val root: View,
    private val original: AutomationRule?,
) {
    /** One entry of the target dropdown: stored value + what the user sees. */
    data class TargetOption(val value: String, val label: String) {
        override fun toString() = label
    }

    private val inflater = LayoutInflater.from(context)

    private val etName: EditText = root.findViewById(R.id.et_name)
    private val spinnerTrigger: Spinner = root.findViewById(R.id.spinner_trigger)
    private val spinnerTargetType: Spinner = root.findViewById(R.id.spinner_target_type)
    private val dividerTargetValue: View = root.findViewById(R.id.divider_target_value)
    private val rowTargetValue: View = root.findViewById(R.id.row_target_value)
    private val tvTargetValueLabel: TextView = root.findViewById(R.id.tv_target_value_label)
    private val spinnerTargetValue: Spinner = root.findViewById(R.id.spinner_target_value)
    private val tvTargetHint: TextView = root.findViewById(R.id.tv_target_hint)
    private val dividerMinDuration: View = root.findViewById(R.id.divider_min_duration)
    private val rowMinDuration: View = root.findViewById(R.id.row_min_duration)
    private val etMinDuration: EditText = root.findViewById(R.id.et_min_duration)
    private val etUrl: EditText = root.findViewById(R.id.et_url)
    private val spinnerMethod: Spinner = root.findViewById(R.id.spinner_method)
    private val headersContainer: LinearLayout = root.findViewById(R.id.headers_container)
    private val sectionBody: View = root.findViewById(R.id.section_body)
    private val radioBodyType: RadioGroup = root.findViewById(R.id.radio_body_type)
    private val fieldsContainer: LinearLayout = root.findViewById(R.id.fields_container)
    private val btnAddField: View = root.findViewById(R.id.btn_add_field)
    private val etBody: EditText = root.findViewById(R.id.et_body)
    private val variablesContainer: LinearLayout = root.findViewById(R.id.variables_container)
    private val tvTestResult: TextView = root.findViewById(R.id.tv_test_result)

    // Loaded off the main thread by the host; null until loaded.
    private var appOptions: List<TargetOption>? = null
    private var awCategoryOptions: List<TargetOption>? = null

    // The target the form currently has selected per type, so switching the type
    // dropdown back and forth does not lose the choice.
    private val selectedTarget = mutableMapOf<TargetType, String>()

    // Where a tapped variable chip is inserted.
    private var lastFocusedField: EditText? = null
    private val focusTracker = View.OnFocusChangeListener { v, hasFocus ->
        if (hasFocus) lastFocusedField = v as EditText
    }

    init {
        val rule = original ?: AutomationRule()
        selectedTarget[rule.targetType] = rule.targetValue

        spinnerTrigger.adapter = rowAdapter(TriggerType.entries.map { it.label })
        spinnerTrigger.setSelection(rule.trigger.ordinal, false)
        spinnerTrigger.onItemSelectedListener = onSelected { updateMinDurationVisibility() }

        spinnerTargetType.adapter = rowAdapter(TargetType.entries.map { it.label })
        spinnerTargetType.setSelection(rule.targetType.ordinal, false)
        spinnerTargetType.onItemSelectedListener = onSelected { renderTargetValues() }

        spinnerTargetValue.onItemSelectedListener = onSelected { position ->
            val option = spinnerTargetValue.adapter.getItem(position) as? TargetOption ?: return@onSelected
            // The "Loading…" placeholder must not overwrite the saved choice.
            if (option.value.isNotEmpty()) selectedTarget[currentTargetType()] = option.value
        }

        spinnerMethod.adapter = rowAdapter(AutomationRule.METHODS)
        spinnerMethod.setSelection(AutomationRule.METHODS.indexOf(rule.method).coerceAtLeast(0), false)
        spinnerMethod.onItemSelectedListener = onSelected { updateBodyVisibility() }

        etName.setText(rule.name)
        etMinDuration.setText(if (rule.minDurationSeconds > 0) rule.minDurationSeconds.toString() else "")
        etUrl.setText(rule.url)
        etBody.setText(rule.body)
        etUrl.onFocusChangeListener = focusTracker
        etBody.onFocusChangeListener = focusTracker

        rule.headers.forEach { addRow(headersContainer, it.key, it.value, null) }
        root.findViewById<View>(R.id.btn_add_header).setOnClickListener {
            addRow(headersContainer, "", "", null).findViewById<View>(R.id.et_row_key).requestFocus()
        }

        rule.bodyFields.forEach { addRow(fieldsContainer, it.key, it.value, it.type) }
        btnAddField.setOnClickListener {
            addRow(fieldsContainer, "", "", FieldType.TEXT).findViewById<View>(R.id.et_row_key).requestFocus()
        }
        radioBodyType.check(
            when (rule.bodyType) {
                BodyType.JSON -> R.id.radio_body_json
                BodyType.FORM -> R.id.radio_body_form
                BodyType.TEXT -> R.id.radio_body_text
            }
        )
        radioBodyType.setOnCheckedChangeListener { _, _ -> updateBodyVisibility() }

        buildVariableChips()
        updateMinDurationVisibility()
        updateBodyVisibility()
        renderTargetValues()
    }

    // --- WHEN card --------------------------------------------------------------------

    private fun currentTargetType() = TargetType.entries[spinnerTargetType.selectedItemPosition]
    private fun currentTrigger() = TriggerType.entries[spinnerTrigger.selectedItemPosition]

    private fun updateMinDurationVisibility() {
        val show = currentTrigger() != TriggerType.OPENED
        rowMinDuration.visibility = if (show) View.VISIBLE else View.GONE
        dividerMinDuration.visibility = rowMinDuration.visibility
    }

    fun setAppOptions(options: List<TargetOption>) {
        appOptions = options
        if (currentTargetType() == TargetType.APP) renderTargetValues()
    }

    fun setAwCategoryOptions(options: List<TargetOption>) {
        awCategoryOptions = options
        if (currentTargetType() == TargetType.AW_CATEGORY) renderTargetValues()
    }

    private fun renderTargetValues() {
        val type = currentTargetType()
        tvTargetHint.visibility = View.GONE
        val showValue = type != TargetType.ANY
        rowTargetValue.visibility = if (showValue) View.VISIBLE else View.GONE
        dividerTargetValue.visibility = rowTargetValue.visibility
        if (!showValue) return
        tvTargetValueLabel.setText(
            when (type) {
                TargetType.APP -> R.string.automation_target_app_label
                TargetType.ANDROID_CATEGORY -> R.string.automation_target_android_category_label
                else -> R.string.automation_target_aw_category_label
            }
        )
        val options: List<TargetOption> = when (type) {
            TargetType.APP -> appOptions ?: placeholderOption("Loading apps…")
            TargetType.ANDROID_CATEGORY -> androidCategoryOptions().also {
                showHint(context.getString(R.string.automation_android_category_hint))
            }
            TargetType.AW_CATEGORY -> awCategoryOptions?.also {
                showHint(context.getString(
                    if (it.isEmpty()) R.string.automation_aw_categories_none else R.string.automation_aw_category_hint
                ))
            } ?: placeholderOption(context.getString(R.string.automation_aw_categories_loading))
            TargetType.ANY -> emptyList()
        }
        spinnerTargetValue.adapter = rowAdapter(options)
        val wanted = selectedTarget[type]
        val idx = options.indexOfFirst { it.value == wanted }
        if (idx >= 0) {
            spinnerTargetValue.setSelection(idx, false)
        } else if (options.isNotEmpty() && options[0].value.isNotEmpty()) {
            selectedTarget[type] = options[0].value
        }
    }

    private fun placeholderOption(label: String) = listOf(TargetOption("", label))

    private fun showHint(text: String) {
        tvTargetHint.text = text
        tvTargetHint.visibility = View.VISIBLE
    }

    private fun androidCategoryOptions(): List<TargetOption> {
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
            TargetOption(it.toString(), AutomationDispatcher.androidCategoryLabel(context, it).ifEmpty { "Category $it" })
        }
    }

    // --- DO card: headers and body rows -------------------------------------------------

    private fun currentBodyType(): BodyType = when (radioBodyType.checkedRadioButtonId) {
        R.id.radio_body_form -> BodyType.FORM
        R.id.radio_body_text -> BodyType.TEXT
        else -> BodyType.JSON
    }

    private fun currentMethod() = AutomationRule.METHODS[spinnerMethod.selectedItemPosition]

    /** Like Shortcuts: GET has no body; Text shows a free-form box; JSON rows have types. */
    private fun updateBodyVisibility() {
        sectionBody.visibility = if (currentMethod() == "GET") View.GONE else View.VISIBLE
        val type = currentBodyType()
        val rows = type != BodyType.TEXT
        fieldsContainer.visibility = if (rows) View.VISIBLE else View.GONE
        btnAddField.visibility = fieldsContainer.visibility
        etBody.visibility = if (rows) View.GONE else View.VISIBLE
        for (i in 0 until fieldsContainer.childCount) {
            fieldsContainer.getChildAt(i).findViewById<View>(R.id.spinner_row_type).visibility =
                if (type == BodyType.JSON) View.VISIBLE else View.GONE
        }
    }

    /** Adds a (-) [key] [type] [value] row; [type] null means no type picker (headers). */
    private fun addRow(container: LinearLayout, key: String, value: String, type: FieldType?): View {
        val row = inflater.inflate(R.layout.item_automation_key_value, container, false)
        val etKey = row.findViewById<EditText>(R.id.et_row_key)
        val etValue = row.findViewById<EditText>(R.id.et_row_value)
        val spinnerType = row.findViewById<Spinner>(R.id.spinner_row_type)
        etKey.setText(key)
        etValue.setText(value)
        etKey.onFocusChangeListener = focusTracker
        etValue.onFocusChangeListener = focusTracker
        if (type == null) {
            spinnerType.visibility = View.GONE
        } else {
            spinnerType.adapter = ArrayAdapter(context, R.layout.item_automation_type_token, FieldType.entries.map { it.label })
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerType.setSelection(type.ordinal, false)
            spinnerType.visibility = if (currentBodyTypeOrNull() == BodyType.JSON) View.VISIBLE else View.GONE
        }
        row.findViewById<View>(R.id.btn_remove_row).setOnClickListener {
            if (lastFocusedField === etKey || lastFocusedField === etValue) lastFocusedField = null
            container.removeView(row)
        }
        container.addView(row)
        return row
    }

    // radioBodyType is checked after the initial rows are added.
    private fun currentBodyTypeOrNull(): BodyType? =
        if (radioBodyType.checkedRadioButtonId == View.NO_ID) null else currentBodyType()

    private fun readRows(container: LinearLayout): List<Triple<String, String, FieldType>> =
        (0 until container.childCount).map { container.getChildAt(it) }.map { row ->
            val spinner = row.findViewById<Spinner>(R.id.spinner_row_type)
            Triple(
                row.findViewById<EditText>(R.id.et_row_key).text.toString().trim(),
                row.findViewById<EditText>(R.id.et_row_value).text.toString(),
                if (spinner.adapter == null) FieldType.TEXT else FieldType.entries[spinner.selectedItemPosition],
            )
        }.filter { it.first.isNotEmpty() }

    // --- Variables --------------------------------------------------------------------

    private fun buildVariableChips() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }
        for (placeholder in RequestTemplate.PLACEHOLDERS) {
            val chip = TextView(context).apply {
                text = placeholder.label
                setTextColor(context.getColor(R.color.automation_chip_text))
                textSize = 14f
                setBackgroundResource(R.drawable.bg_automation_chip)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { insertVariable(placeholder.token) }
                setOnLongClickListener {
                    Toast.makeText(context, "${placeholder.token}: ${placeholder.description}", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            variablesContainer.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8) },
            )
        }
    }

    private fun insertVariable(token: String) {
        val field = lastFocusedField?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
        if (field == null) {
            Toast.makeText(context, R.string.automation_select_field_first, Toast.LENGTH_SHORT).show()
            return
        }
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(0)
        field.text.replace(minOf(start, end), maxOf(start, end), token)
        field.requestFocus()
    }

    // --- Reading the form ------------------------------------------------------------

    /** Reads the form into a rule, or shows what's wrong and returns null. */
    fun read(): AutomationRule? {
        val type = currentTargetType()
        val targetValue = if (type == TargetType.ANY) "" else selectedTarget[type].orEmpty()
        if (type != TargetType.ANY && targetValue.isEmpty()) {
            Toast.makeText(context, "Choose which ${tvTargetValueLabel.text.toString().lowercase()} this applies to", Toast.LENGTH_SHORT).show()
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
            .mapNotNull { spinnerTargetValue.adapter.getItem(it) as? TargetOption }
            .firstOrNull { it.value == targetValue }?.label.orEmpty()
        return (original ?: AutomationRule()).copy(
            name = etName.text.toString().trim().ifEmpty { defaultName(type, targetLabel) },
            trigger = currentTrigger(),
            targetType = type,
            targetValue = targetValue,
            targetLabel = if (type == TargetType.ANY) "" else targetLabel,
            minDurationSeconds = etMinDuration.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            method = currentMethod(),
            url = url,
            headers = readRows(headersContainer).map { KeyValue(it.first, it.second.trim()) },
            bodyType = currentBodyType(),
            bodyFields = readRows(fieldsContainer).map { BodyField(it.first, it.second, it.third) },
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

    private fun withoutPlaceholders(url: String) = url.replace(Regex("""\{\{[^}]*\}\}"""), "x")

    private fun validateUrl(url: String): String? {
        if (url.isEmpty()) return "Enter the URL to call"
        // Placeholders are allowed in the URL; validate with sample values substituted.
        val probe = Uri.parse(withoutPlaceholders(url))
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
    fun cleartextNotice(url: String): String? {
        val probe = Uri.parse(withoutPlaceholders(url))
        if (probe.scheme?.lowercase() != "http") return null
        val host = probe.host ?: return null
        if (LocalNetwork.classifyHost(host) != LocalNetwork.HostKind.UNKNOWN) return null
        return "Plain http:// requests to $host are only sent if it resolves to a local-network address."
    }

    fun showTestResult(text: CharSequence) {
        tvTestResult.visibility = View.VISIBLE
        tvTestResult.text = text
    }

    // --- Helpers ----------------------------------------------------------------------

    /** Right-aligned value in a settings-style row, with the standard dropdown list. */
    private fun <T> rowAdapter(items: List<T>) =
        ArrayAdapter(context, R.layout.item_automation_spinner_value, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onSelected(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
