package com.eliminatefluff

import android.content.Context

/** Persists how many times the blocker has kicked you out. */
object Stats {
    private const val PREFS = "stats"
    private const val KEY_BLOCKS = "blocks"

    fun blockCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BLOCKS, 0)

    fun recordBlock(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BLOCKS, prefs.getInt(KEY_BLOCKS, 0) + 1).apply()
    }
}
