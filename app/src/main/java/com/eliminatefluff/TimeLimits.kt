package com.eliminatefluff

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * The time limits you set in the app, for the apps in [apps].
 *
 * @param dailyMinutes how long you can use all of [apps] together per day
 * @param sessionMinutes how long you can use an app in a row before [cooldownMinutes] of
 *   lockout. A break only ends the run if it lasts the whole cooldown, so you can't get
 *   around it by leaving the app for a moment.
 */
data class Limits(
    val apps: Set<String> = DEFAULT_APPS,
    val dailyEnabled: Boolean = false,
    val dailyMinutes: Int = 120,
    val sessionEnabled: Boolean = false,
    val sessionMinutes: Int = 20,
    val cooldownMinutes: Int = 60,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_APPS, apps)
            .putBoolean(KEY_DAILY_ENABLED, dailyEnabled)
            .putInt(KEY_DAILY_MINUTES, dailyMinutes)
            .putBoolean(KEY_SESSION_ENABLED, sessionEnabled)
            .putInt(KEY_SESSION_MINUTES, sessionMinutes)
            .putInt(KEY_COOLDOWN_MINUTES, cooldownMinutes)
            .apply()
    }

    companion object {
        private const val PREFS = "limits"
        private const val KEY_APPS = "apps"
        private const val KEY_DAILY_ENABLED = "daily_enabled"
        private const val KEY_DAILY_MINUTES = "daily_minutes"
        private const val KEY_SESSION_ENABLED = "session_enabled"
        private const val KEY_SESSION_MINUTES = "session_minutes"
        private const val KEY_COOLDOWN_MINUTES = "cooldown_minutes"

        val DEFAULT_APPS = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.reddit.frontpage",
        )

        fun load(context: Context): Limits {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = Limits()
            return Limits(
                apps = prefs.getStringSet(KEY_APPS, null)?.toSet() ?: defaults.apps,
                dailyEnabled = prefs.getBoolean(KEY_DAILY_ENABLED, defaults.dailyEnabled),
                dailyMinutes = prefs.getInt(KEY_DAILY_MINUTES, defaults.dailyMinutes),
                sessionEnabled = prefs.getBoolean(KEY_SESSION_ENABLED, defaults.sessionEnabled),
                sessionMinutes = prefs.getInt(KEY_SESSION_MINUTES, defaults.sessionMinutes),
                cooldownMinutes = prefs.getInt(KEY_COOLDOWN_MINUTES, defaults.cooldownMinutes),
            )
        }
    }
}

/**
 * Persists how long you've spent in each limited app, and works out when an app is
 * locked. Times are wall-clock milliseconds so cooldowns carry over a reboot.
 */
class AppUsage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun todayMs(pkg: String): Long {
        resetIfNewDay()
        return prefs.getLong(KEY_TODAY + pkg, 0L)
    }

    /** Today's time across all limited apps, which is what the daily limit counts. */
    fun todayTotalMs(limits: Limits): Long = limits.apps.sumOf(::todayMs)

    /** Time in the current run of use, which ends after a break as long as the cooldown. */
    fun sessionMs(pkg: String, limits: Limits, now: Long = System.currentTimeMillis()): Long {
        val lastUsed = prefs.getLong(KEY_LAST_USED + pkg, 0L)
        if (now - lastUsed >= limits.cooldownMinutes * MINUTE_MS) return 0L
        return prefs.getLong(KEY_SESSION + pkg, 0L)
    }

    fun add(pkg: String, ms: Long, limits: Limits, now: Long = System.currentTimeMillis()) {
        val today = todayMs(pkg)
        val session = sessionMs(pkg, limits, now)
        prefs.edit()
            .putLong(KEY_TODAY + pkg, today + ms)
            .putLong(KEY_SESSION + pkg, session + ms)
            .putLong(KEY_LAST_USED + pkg, now)
            .apply()
    }

    /** When [pkg] unlocks again (wall-clock ms), or null if it isn't locked. */
    fun lockedUntil(pkg: String, limits: Limits, now: Long = System.currentTimeMillis()): Long? {
        if (pkg !in limits.apps) return null
        val untils = buildList {
            if (limits.dailyEnabled && todayTotalMs(limits) >= limits.dailyMinutes * MINUTE_MS) {
                add(nextMidnight())
            }
            if (limits.sessionEnabled && sessionMs(pkg, limits, now) >= limits.sessionMinutes * MINUTE_MS) {
                add(prefs.getLong(KEY_LAST_USED + pkg, now) + limits.cooldownMinutes * MINUTE_MS)
            }
        }
        return untils.maxOrNull()
    }

    /** How long until [pkg] gets locked, or null if no limit applies to it. */
    fun remainingMs(pkg: String, limits: Limits, now: Long = System.currentTimeMillis()): Long? {
        if (pkg !in limits.apps) return null
        val remaining = buildList {
            if (limits.dailyEnabled) add(limits.dailyMinutes * MINUTE_MS - todayTotalMs(limits))
            if (limits.sessionEnabled) add(limits.sessionMinutes * MINUTE_MS - sessionMs(pkg, limits, now))
        }
        return remaining.minOrNull()
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) == today) return
        val editor = prefs.edit().putString(KEY_DAY, today)
        prefs.all.keys.filter { it.startsWith(KEY_TODAY) }.forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val PREFS = "usage"
        private const val KEY_DAY = "day"
        private const val KEY_TODAY = "today:"
        private const val KEY_SESSION = "session:"
        private const val KEY_LAST_USED = "last_used:"
        const val MINUTE_MS = 60_000L
    }
}

fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** "14:30", or "tomorrow" when it's midnight tonight or later. */
fun formatUnlockTime(context: Context, at: Long): String =
    if (at >= nextMidnight()) context.getString(R.string.unlock_tomorrow)
    else DateFormat.getTimeFormat(context).format(Date(at))

private fun nextMidnight(): Long =
    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
