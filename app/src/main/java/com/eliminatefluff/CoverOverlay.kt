package com.eliminatefluff

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * @param touchable whether the cover swallows touches (true) or lets them through to the app,
 *   e.g. so the feed can still be scrolled past a hidden post
 * @param transparent an invisible cover: what is under it stays visible but can't be tapped
 */
data class Cover(
    val bounds: Rect,
    val touchable: Boolean,
    val label: String? = null,
    val transparent: Boolean = false,
)

/** Solid blocks drawn on top of other apps, used to hide parts of their screens. */
class CoverOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<TextView>()

    fun show(covers: List<Cover>, dark: Boolean) {
        while (views.size > covers.size) windowManager.removeView(views.removeAt(views.lastIndex))

        covers.forEachIndexed { i, cover ->
            val state = cover to dark
            val view = views.getOrNull(i)
            if (view?.tag == state) return@forEachIndexed

            val params = layoutParams(cover)
            if (view == null) {
                views += TextView(context).also {
                    it.style(cover, dark)
                    windowManager.addView(it, params)
                }
            } else {
                view.style(cover, dark)
                windowManager.updateViewLayout(view, params)
            }
        }
    }

    fun clear() = show(emptyList(), dark = false)

    private fun TextView.style(cover: Cover, dark: Boolean) {
        tag = cover to dark
        text = cover.label
        gravity = Gravity.CENTER
        textSize = 16f
        setPadding(48, 0, 48, 0)
        setBackgroundColor(
            when {
                cover.transparent -> Color.TRANSPARENT
                dark -> Color.BLACK
                else -> Color.WHITE
            }
        )
        setTextColor(Color.GRAY)
    }

    private fun layoutParams(cover: Cover): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!cover.touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(
            cover.bounds.width(),
            cover.bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            if (cover.transparent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cover.bounds.left
            y = cover.bounds.top
        }
    }
}
