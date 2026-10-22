package com.eliminatefluff

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Works out which parts of the Instagram home feed belong to posts from accounts you
 * don't follow (suggested posts and ads). Those posts have a Follow button or an "Ad"
 * label in their header.
 *
 * A post is a run of rows starting at its header, so a post's area runs from its header
 * down to the next header. The rows above the first visible header belong to a post whose
 * header has scrolled off screen; we remember which header came before which to know
 * whether to hide them.
 */
class FeedPostTracker {

    private class Header(val key: String, val bounds: Rect, val unfollowed: Boolean)

    /** Header key -> whether the post just above it is from an account you don't follow. */
    private val previousPostUnfollowed = HashMap<String, Boolean>()
    private var lastTopHeader: Header? = null

    fun reset() {
        previousPostUnfollowed.clear()
        lastTopHeader = null
    }

    fun hiddenRegions(root: AccessibilityNodeInfo): List<Rect> {
        val pkg = root.packageName
        val feed = root.findAccessibilityNodeInfosByViewId("$pkg:id/$FEED_ID")
            .firstOrNull { it.isVisibleToUser }
            ?.screenBounds() ?: return emptyList()

        val headers = root.findAccessibilityNodeInfosByViewId("$pkg:id/$HEADER_ID")
            .filter { it.isVisibleToUser }
            .map { Header(it.contentDescription?.toString().orEmpty(), it.screenBounds(), it.isUnfollowed(pkg)) }
            .sortedBy { it.bounds.top }

        if (headers.isEmpty()) {
            // Mid-way through a post taller than the screen: it belongs to the header that
            // scrolled off the top, or (scrolling up) to the post before the one that went off the bottom.
            val last = lastTopHeader ?: return emptyList()
            val unfollowed = if (last.bounds.top < feed.centerY()) {
                last.unfollowed
            } else {
                previousPostUnfollowed[last.key] == true
            }
            return if (unfollowed) listOf(feed) else emptyList()
        }

        for ((above, below) in headers.zipWithNext()) {
            previousPostUnfollowed[below.key] = above.unfollowed
        }
        lastTopHeader = headers.first()

        val regions = mutableListOf<Rect>()
        if (previousPostUnfollowed[headers.first().key] == true) {
            regions += Rect(feed.left, feed.top, feed.right, headers.first().bounds.top)
        }
        headers.forEachIndexed { i, header ->
            if (header.unfollowed) {
                val bottom = headers.getOrNull(i + 1)?.bounds?.top ?: feed.bottom
                regions += Rect(feed.left, header.bounds.top, feed.right, bottom)
            }
        }
        return regions.filter { it.intersect(feed) && !it.isEmpty }
    }

    private fun AccessibilityNodeInfo.isUnfollowed(pkg: CharSequence): Boolean =
        findAccessibilityNodeInfosByViewId("$pkg:id/$FOLLOW_BUTTON_ID").any { it.isVisibleToUser } ||
            findAccessibilityNodeInfosByViewId("$pkg:id/$SUBTITLE_ID").any {
                it.text?.toString()?.trim() in AD_LABELS
            }

    private fun AccessibilityNodeInfo.screenBounds() = Rect().also(::getBoundsInScreen)

    companion object {
        // Ids come from Instagram's internals and can change with updates; see README.
        private const val FEED_ID = "refreshable_container"
        private const val HEADER_ID = "row_feed_profile_header"
        private const val FOLLOW_BUTTON_ID = "inline_follow_button"
        private const val SUBTITLE_ID = "secondary_label"
        private val AD_LABELS = setOf("Ad", "Sponsored")
    }
}
