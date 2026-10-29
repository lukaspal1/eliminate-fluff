package com.eliminatefluff

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val usage by lazy { AppUsage(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val limits = Limits.load(this)
        findViewById<Switch>(R.id.daily_switch).apply {
            isChecked = limits.dailyEnabled
            setOnCheckedChangeListener { _, on -> updateLimits { it.copy(dailyEnabled = on) } }
        }
        findViewById<Switch>(R.id.session_switch).apply {
            isChecked = limits.sessionEnabled
            setOnCheckedChangeListener { _, on -> updateLimits { it.copy(sessionEnabled = on) } }
        }
        bindMinutes(R.id.daily_minutes, limits.dailyMinutes) { l, m -> l.copy(dailyMinutes = m) }
        bindMinutes(R.id.session_minutes, limits.sessionMinutes) { l, m -> l.copy(sessionMinutes = m) }
        bindMinutes(R.id.cooldown_minutes, limits.cooldownMinutes) { l, m -> l.copy(cooldownMinutes = m) }
        findViewById<Button>(R.id.choose_apps).setOnClickListener { chooseApps() }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isBlockerEnabled()
        findViewById<TextView>(R.id.status).setText(
            if (enabled) R.string.status_on else R.string.status_off
        )
        findViewById<Button>(R.id.open_settings).setText(
            if (enabled) R.string.button_manage else R.string.button_enable
        )
        findViewById<TextView>(R.id.block_count).text =
            getString(R.string.block_count, Stats.blockCount(this))
        showLimitedApps()
    }

    private fun updateLimits(change: (Limits) -> Limits) {
        change(Limits.load(this)).save(this)
        showLimitedApps()
    }

    /** Saves the field whenever it holds a whole number of minutes above zero. */
    private fun bindMinutes(id: Int, value: Int, change: (Limits, Int) -> Limits) {
        val field = findViewById<EditText>(id)
        field.setText(value.toString())
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val minutes = s?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return
                updateLimits { change(it, minutes) }
            }
        })
    }

    /** Lets you pick limited apps from everything with a launcher icon. */
    private fun chooseApps() {
        val selected = Limits.load(this).apps
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { it to appLabel(this, it) }
            .sortedWith(compareBy({ it.first !in selected }, { it.second.lowercase() }))
        val checked = BooleanArray(apps.size) { apps[it].first in selected }

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_apps_title)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val listed = apps.map { it.first }.toSet()
                // Keep apps that are limited but not installed right now.
                val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                updateLimits { it.copy(apps = it.apps - listed + chosen) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLimitedApps() {
        val limits = Limits.load(this)
        val installed = limits.apps.filter { runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }
            .sortedBy { appLabel(this, it).lowercase() }

        findViewById<Button>(R.id.choose_apps).text =
            if (installed.isEmpty()) getString(R.string.choose_apps_none)
            else getString(R.string.choose_apps, installed.joinToString { appLabel(this, it) })

        val total = (usage.todayTotalMs(limits) / AppUsage.MINUTE_MS).toInt()
        findViewById<TextView>(R.id.usage_summary).text = installed.joinToString(
            "\n", prefix = getString(R.string.usage_total, total) + "\n",
        ) { pkg ->
            val label = appLabel(this, pkg)
            val minutes = (usage.todayMs(pkg) / AppUsage.MINUTE_MS).toInt()
            val lockedUntil = usage.lockedUntil(pkg, limits)
            if (lockedUntil == null) getString(R.string.usage_line, label, minutes)
            else getString(R.string.usage_line_locked, label, minutes, formatUnlockTime(this, lockedUntil))
        }
    }

    private fun isBlockerEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, FluffBlockerService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
    }
}
