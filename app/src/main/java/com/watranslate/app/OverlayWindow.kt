package com.watranslate.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a small draggable floating card on top of WhatsApp (or any app) showing:
 *   - the original recognized Indonesian text
 *   - the Urdu translation
 *   - the English translation
 *
 * Requires SYSTEM_ALERT_WINDOW permission to already be granted before show().
 */
class OverlayWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var tvOriginal: TextView? = null
    private var tvUrdu: TextView? = null
    private var tvEnglish: TextView? = null

    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (rootView != null) return // already showing

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }
        params = layoutParams

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#E6128C7E")) // WhatsApp-green, semi-transparent
        }

        tvOriginal = TextView(context).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            text = "Listening…"
        }
        tvUrdu = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 8, 0, 0)
            text = ""
        }
        tvEnglish = TextView(context).apply {
            setTextColor(Color.parseColor("#DDFFFFFF"))
            textSize = 14f
            setPadding(0, 4, 0, 0)
            text = ""
        }

        container.addView(tvOriginal)
        container.addView(tvUrdu)
        container.addView(tvEnglish)

        makeDraggable(container, layoutParams)

        rootView = container
        windowManager.addView(container, layoutParams)
    }

    fun updateText(original: String, urdu: String, english: String) {
        tvOriginal?.text = original
        tvUrdu?.text = urdu
        tvEnglish?.text = english
    }

    fun setListening(isListening: Boolean) {
        tvOriginal?.text = if (isListening) "Listening…" else "Paused"
    }

    fun hide() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // view might already be detached
            }
        }
        rootView = null
    }

    /** Lets the user drag the floating bubble anywhere on screen. */
    private fun makeDraggable(view: View, layoutParams: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - touchX).toInt()
                    layoutParams.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(v, layoutParams)
                    true
                }
                else -> false
            }
        }
    }
}
