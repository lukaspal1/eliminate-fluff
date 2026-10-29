package com.eliminatefluff

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * - Lets you watch a single Reel or Short (from your feed, a DM, Explore...) but presses
 *   Back as soon as you swipe to the next one.
 * - Covers the Reels / Shorts tab in the bottom bar so it can't be tapped.
 * - Covers Instagram's Explore grid (searching still works).
 * - Times the apps you've set limits for and sends you home once one runs out (see [Limits]).
 */
class FluffBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable(::refreshCovers)
    private val covers by lazy { CoverOverlay(this) }

    private var lastBlockAt = 0L
    /** When the current Reels/Shorts player appeared, or 0 when none is showing. */
    private var playerOpenedAt = 0L

    private val usage by lazy { AppUsage(this) }
    private val usageTick = Runnable(::onUsageTick)
    /** The limited app being timed, and when its time was last counted. */
    private var timedPkg: String? = null
    private var lastTickAt = 0L
    private var lastLockOutAt = 0L
    private val warned = mutableSetOf<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (!handler.hasCallbacks(usageTick) && pkg in Limits.load(this).apps) handler.post(usageTick)

        val target = TARGETS[pkg]
        if (target == null) {
            // Switched to another app (home screen, notifications...): drop the covers.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                playerOpenedAt = 0L
                covers.clear()
            }
            return
        }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        if (BuildConfig.DEBUG && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            dumpViewIds(root)
        }

        val now = SystemClock.uptimeMillis()
        val playerVisible = target.playerIds.any { root.hasVisibleView(pkg, it) }
        playerOpenedAt = when {
            !playerVisible -> 0L
            playerOpenedAt == 0L -> now
            else -> playerOpenedAt
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event, target, now)
        }

        // At most one refresh per interval, but always one after the last event.
        if (!handler.hasCallbacks(refresh)) handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
    }

    private fun onScrolled(event: AccessibilityEvent, target: Target, now: Long) {
        val sourceId = event.source?.viewIdResourceName?.substringAfter(":id/") ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "Scrolled: $sourceId")

        // Opening a player can scroll it into position; only count swipes after it settles.
        if (playerOpenedAt == 0L || now - playerOpenedAt < PLAYER_SETTLE_MS) return
        if (now - lastBlockAt < COOLDOWN_MS) return
        if (sourceId !in target.pagerIds) return

        lastBlockAt = now
        playerOpenedAt = 0L
        performGlobalAction(GLOBAL_ACTION_BACK)
        Stats.recordBlock(this)
        Log.i(TAG, "Blocked scrolling in ${event.packageName}")
    }

    /**
     * Runs every second while a limited app is on screen: counts the time spent in it,
     * warns a minute before a limit, and sends you to the home screen once it's locked.
     */
    private fun onUsageTick() {
        val now = SystemClock.elapsedRealtime()
        val limits = Limits.load(this)
        val pkg = rootInActiveWindow?.packageName?.toString()
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (pkg == null || pkg !in limits.apps || !interactive) {
            timedPkg = null
            return
        }

        if (pkg == timedPkg) usage.add(pkg, (now - lastTickAt).coerceAtMost(MAX_TICK_GAP_MS), limits)
        timedPkg = pkg
        lastTickAt = now

        val lockedUntil = usage.lockedUntil(pkg, limits)
        if (lockedUntil != null) {
            lockOut(pkg, lockedUntil)
        } else {
            val remaining = usage.remainingMs(pkg, limits)
            if (remaining != null && remaining <= WARNING_MS) {
                if (warned.add(pkg)) toast(getString(R.string.limit_warning, appLabel(this, pkg)))
            } else {
                warned -= pkg
            }
        }
        handler.postDelayed(usageTick, USAGE_TICK_MS)
    }

    private fun lockOut(pkg: String, until: Long) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockOutAt < LOCK_OUT_TOAST_GAP_MS) return
        lastLockOutAt = now
        toast(getString(R.string.locked_out, appLabel(this, pkg), formatUnlockTime(this, until)))
        Log.i(TAG, "Locked out of $pkg")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun refreshCovers() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val target = TARGETS[pkg]
        if (root == null || pkg == null || target == null) {
            covers.clear()
            return
        }

        val list = buildList {
            findTab(root, target)?.let { add(Cover(it, touchable = true, transparent = true)) }

            if (pkg == INSTAGRAM && root.isTabSelected(pkg, "search_tab")) {
                exploreGrid(root)?.let { add(Cover(it, touchable = true, getString(R.string.explore_hidden))) }
            }
        }

        // The Reels/Shorts player is always dark; elsewhere follow the system theme.
        val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        covers.show(list, dark = isNight || playerOpenedAt != 0L)
    }

    /** Screen bounds of the Reels / Shorts button in the bottom bar, if it's on screen. */
    private fun findTab(root: AccessibilityNodeInfo, target: Target): Rect? {
        val bottomBarTop = resources.displayMetrics.heightPixels * 0.8f
        val candidates = buildList {
            target.tabId?.let { addAll(root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$it")) }
            target.tabLabel?.let { label ->
                root.findAccessibilityNodeInfosByText(label).filterTo(this) {
                    it.text?.toString().equals(label, ignoreCase = true) ||
                        it.contentDescription?.toString().equals(label, ignoreCase = true)
                }
            }
        }
        return candidates.asSequence()
            .filter { it.isVisibleToUser }
            .map { (it.clickableAncestor() ?: it).screenBounds() }
            .firstOrNull { it.top >= bottomBarTop && !it.isEmpty }
    }

    /**
     * The area of Instagram's Explore grid: everything between the search box and the
     * bottom bar, while the search box is empty and not being typed in.
     */
    private fun exploreGrid(root: AccessibilityNodeInfo): Rect? {
        val tabBar = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/tab_bar")
            .firstOrNull { it.isVisibleToUser } ?: return null
        val searchBox = root.findFirst {
            it.className == "android.widget.EditText" && it.isVisibleToUser &&
                it.screenBounds().top < resources.displayMetrics.heightPixels * 0.25f
        } ?: return null
        if (searchBox.isFocused || !(searchBox.isShowingHintText || searchBox.text.isNullOrEmpty())) return null

        val top = searchBox.screenBounds().bottom
        val bottom = tabBar.screenBounds().top
        return Rect(0, top, resources.displayMetrics.widthPixels, bottom).takeIf { !it.isEmpty }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(usageTick)
        covers.clear()
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.hasVisibleView(pkg: String, id: String): Boolean =
        findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isVisibleToUser }

    private fun AccessibilityNodeInfo.isTabSelected(pkg: String, id: String): Boolean =
        findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isSelected }

    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = this
        while (node != null && !node.isClickable) node = node.parent
        return node
    }

    private fun AccessibilityNodeInfo.findFirst(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        for (i in 0 until childCount) getChild(i)?.findFirst(predicate)?.let { return it }
        return null
    }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also(::getBoundsInScreen)

    /** Logs every view id on screen, to find new ids when an app update breaks detection. */
    private fun dumpViewIds(root: AccessibilityNodeInfo) {
        val ids = sortedSetOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            node.viewIdResourceName?.let(ids::add)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        Log.d(TAG, "View ids on screen: ${ids.joinToString()}")
    }

    /**
     * @param playerIds views that only exist inside the full-screen Reels / Shorts player
     * @param pagerIds the vertically swiping list inside that player
     * @param tabId / [tabLabel] how to find the Reels / Shorts button in the bottom bar
     */
    private class Target(
        val playerIds: List<String>,
        val pagerIds: List<String>,
        val tabId: String? = null,
        val tabLabel: String? = null,
    )

    companion object {
        private const val TAG = "FluffBlocker"
        private const val COOLDOWN_MS = 600L
        private const val PLAYER_SETTLE_MS = 800L
        private const val REFRESH_INTERVAL_MS = 50L
        private const val USAGE_TICK_MS = 1_000L
        /** Longest gap between ticks that still counts as use, e.g. if the phone was asleep. */
        private const val MAX_TICK_GAP_MS = 5_000L
        private const val WARNING_MS = 60_000L
        private const val LOCK_OUT_TOAST_GAP_MS = 3_000L
        private const val INSTAGRAM = "com.instagram.android"

        /**
         * Ids come from the apps' internals and can change with updates; see README.
         */
        private val TARGETS = mapOf(
            INSTAGRAM to Target(
                playerIds = listOf("clips_viewer_view_pager", "clips_viewer_container"),
                pagerIds = listOf("clips_viewer_view_pager"),
                tabId = "clips_tab",
            ),
            "com.google.android.youtube" to Target(
                playerIds = listOf("reel_recycler", "reel_player_page_container", "reel_watch_player"),
                pagerIds = listOf("reel_recycler"),
                tabLabel = "Shorts",
            ),
        )
    }
}
