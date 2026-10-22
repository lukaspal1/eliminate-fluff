package com.eliminatefluff

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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
    }

    private fun isBlockerEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, FluffBlockerService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(component, ignoreCase = true) }
    }
}
